package io.quarkus.arc.arquillian;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.enterprise.inject.Stereotype;
import jakarta.inject.Qualifier;
import jakarta.interceptor.InterceptorBinding;

import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Indexer;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.asset.ArchiveAsset;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.WebArchive;

import io.quarkus.arc.processor.AnnotationsTransformer;
import io.quarkus.arc.processor.BeanArchives;
import io.quarkus.arc.processor.BeanDefiningAnnotation;
import io.quarkus.arc.processor.BeanProcessor;
import io.quarkus.arc.processor.cdi.build.compatible.extensions.ExtensionsEntryPoint;

final class Deployer {
    private final Archive<?> deploymentArchive;
    private final DeploymentDir deploymentDir;
    private final String testClass;

    private final Set<String> beanArchivePaths = new HashSet<>();

    Deployer(Archive<?> deploymentArchive, DeploymentDir deploymentDir, String testClass) {
        this.deploymentArchive = deploymentArchive;
        this.deploymentDir = deploymentDir;
        this.testClass = testClass;
    }

    DeploymentClassLoader deploy() throws DeploymentException {
        try {
            if (deploymentArchive instanceof WebArchive) {
                explodeWar();
            } else {
                throw new DeploymentException("Unknown archive type: " + deploymentArchive);
            }

            generate();

            return new DeploymentClassLoader(deploymentDir);
        } catch (IOException | ExecutionException | InterruptedException e) {
            throw new DeploymentException("Deployment failed", e);
        }
    }

    private List<String> findBeansXml(List<String> beanArchivePrefixes, Archive<?> archive, String archivePrefix)
            throws IOException {
        for (Map.Entry<ArchivePath, Node> entry : archive.getContent().entrySet()) {
            if (entry.getValue() == null || entry.getValue().getAsset() == null) {
                continue;
            }
            Asset asset = entry.getValue().getAsset();
            if (asset instanceof ArchiveAsset) {
                // this is a JAR what we want to check for beans.xml as well
                findBeansXml(beanArchivePrefixes, ((ArchiveAsset) asset).getArchive(), entry.getKey().get());
            } else if (isBeanArchive(entry.getValue().getAsset())) {
                String beansXmlPath = archivePrefix + entry.getKey().get();
                if (beansXmlPath.endsWith("/META-INF/beans.xml")) {
                    beanArchivePrefixes.add(beansXmlPath.replace("/META-INF/beans.xml", ""));
                } else if (beansXmlPath.endsWith("WEB-INF/beans.xml")) {
                    beanArchivePrefixes.add(beansXmlPath.replace("WEB-INF/beans.xml", "WEB-INF/classes"));
                }
            }

        }
        return beanArchivePrefixes;
    }

    private void explodeWar() throws IOException {
        List<String> beanArchivePrefixes = new ArrayList<>();
        // identify all archives that are bean archives
        findBeansXml(beanArchivePrefixes, deploymentArchive, "");

        for (Map.Entry<ArchivePath, Node> entry : deploymentArchive.getContent().entrySet()) {
            Asset asset = entry.getValue().getAsset();
            if (asset == null) {
                continue;
            }

            boolean isInBeanArchive = beanArchivePrefixes.stream()
                    .anyMatch(it -> entry.getKey().get().startsWith(it));
            if (entry.getKey().get().endsWith(testClass.replace('.', '/') + ".class")) {
                // the test class is always a bean
                isInBeanArchive = true;
            }

            String path = entry.getKey().get();
            if (path.startsWith("/WEB-INF/classes/")) {
                String classFile = path.replace("/WEB-INF/classes/", "");
                Path classFilePath = deploymentDir.appClasses.resolve(classFile);
                copy(asset, classFilePath);
                if (isInBeanArchive) {
                    beanArchivePaths.add(classFile);
                }
            } else if (path.startsWith("/WEB-INF/lib/")) {
                String jarFile = path.replace("/WEB-INF/lib/", "");
                Path jarFilePath = deploymentDir.appLibraries.resolve(jarFile);
                // TODO why copy whole JAR if we then dost scan it anyway?
                copy(asset, jarFilePath);
                // TODO this jar can have beans in it and we can tell if that's the case, how do we add them?
                if (isInBeanArchive) {
                    for (Map.Entry<ArchivePath, Node> jarEntry : ((ArchiveAsset) asset).getArchive().getContent().entrySet()) {
                        if (jarEntry.getValue() == null || jarEntry.getValue().getAsset() == null
                                || jarEntry.getKey().get().contains("beans.xml")) {
                            continue;
                        }
                        String pathInsideJar = jarEntry.getKey().get().substring(1); // remove first "/"
                        Path classFilePath = deploymentDir.appClasses.resolve(pathInsideJar);
                        copy(asset, classFilePath);
                        System.err.println("class from JAR that's supposed to be a bean " + pathInsideJar);
                        beanArchivePaths.add(pathInsideJar);
                    }
                }
            }
        }
    }

    private boolean isBeanArchive(Asset beansXml) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = beansXml.openStream()) {
            in.transferTo(bytes);
        }
        String content = bytes.toString(StandardCharsets.UTF_8);

        if (content.trim().isEmpty()) {
            return true;
        }

        if (content.contains("bean-discovery-mode='annotated'")
                || content.contains("bean-discovery-mode=\"annotated\"")) {
            return true;
        }

        if (content.contains("bean-discovery-mode='none'")
                || content.contains("bean-discovery-mode=\"none\"")) {
            return false;
        }

        if (content.contains("bean-discovery-mode='all'")
                || content.contains("bean-discovery-mode=\"all\"")) {
            throw new IllegalStateException("Bean discovery mode of 'all' not supported in CDI Lite");
        }

        // bean discovery mode not present, defaults to `annotated`
        return true;
    }

    private void copy(Asset asset, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent()); // make sure the directory exists
        try (InputStream in = asset.openStream()) {
            Files.copy(in, targetPath);
        }
    }

    private void generate() throws IOException, ExecutionException, InterruptedException {
        Index applicationIndex = buildApplicationIndex();

        try (Closeable ignored = withDeploymentClassLoader()) {
            ExtensionsEntryPoint buildCompatibleExtensions = new ExtensionsEntryPoint();
            Set<String> additionalClasses = new HashSet<>();
            buildCompatibleExtensions.runDiscovery(applicationIndex, additionalClasses);

            IndexView beanArchiveIndex = buildImmutableBeanArchiveIndex(applicationIndex, additionalClasses);

            BeanProcessor beanProcessor = BeanProcessor.builder()
                    .setName(deploymentDir.root.getFileName().toString())
                    .setImmutableBeanArchiveIndex(beanArchiveIndex)
                    .setComputingBeanArchiveIndex(BeanArchives.buildComputingBeanArchiveIndex(
                            Thread.currentThread().getContextClassLoader(), new ConcurrentHashMap<>(),
                            beanArchiveIndex))
                    .setApplicationIndex(applicationIndex)
                    .setBuildCompatibleExtensions(buildCompatibleExtensions)
                    .setAdditionalBeanDefiningAnnotations(Set.of(new BeanDefiningAnnotation(
                            DotName.createSimple(ExtraBean.class.getName()), null)))
                    .addAnnotationTransformer(new AnnotationsTransformer() {
                        @Override
                        public boolean appliesTo(AnnotationTarget.Kind kind) {
                            return kind == AnnotationTarget.Kind.CLASS;
                        }

                        @Override
                        public void transform(TransformationContext ctx) {
                            if (ctx.getTarget().asClass().name().toString().equals(testClass)) {
                                // make the test class a bean
                                ctx.transform().add(ExtraBean.class).done();
                            }
                            if (additionalClasses.contains(ctx.getTarget().asClass().name().toString())) {
                                // make all the `@Discovery`-registered classes beans
                                ctx.transform().add(ExtraBean.class).done();
                            }
                        }
                    })
                    .setOutput(resource -> {
                        switch (resource.getType()) {
                            case JAVA_CLASS:
                                resource.writeTo(deploymentDir.generatedClasses.toFile());
                                break;
                            case SERVICE_PROVIDER:
                                resource.writeTo(deploymentDir.generatedServices.toFile());
                                break;
                            default:
                                throw new IllegalArgumentException("Unknown resource type " + resource.getType());
                        }
                    })
                    .build();
            beanProcessor.process();
        }
    }

    private Index buildApplicationIndex() throws IOException {
        Indexer indexer = new Indexer();
        try (Stream<Path> appClasses = Files.walk(deploymentDir.appClasses)) {
            List<Path> classFiles = appClasses.filter(it -> it.toString().endsWith(".class")).collect(Collectors.toList());
            for (Path classFile : classFiles) {
                try (InputStream in = Files.newInputStream(classFile)) {
                    indexer.index(in);
                }
            }
        }
        return indexer.complete();
    }

    private IndexView buildImmutableBeanArchiveIndex(Index applicationIndex, Set<String> additionalClasses) throws IOException {
        Indexer indexer = new Indexer();

        // 1. classes found during type discovery
        for (String beanArchivePath : beanArchivePaths) {
            Path classFile = deploymentDir.appClasses.resolve(beanArchivePath);
            if (!classFile.toString().endsWith(".class")) {
                continue;
            }
            try (InputStream in = Files.newInputStream(classFile)) {
                indexer.index(in);
            }
        }

        // 2. additional classes added through build compatible extensions
        for (String additionalClass : additionalClasses) {
            String classFile = additionalClass.replace('.', '/') + ".class";
            if (beanArchivePaths.contains(classFile)) {
                continue;
            }
            try (InputStream in = Files.newInputStream(deploymentDir.appClasses.resolve(classFile))) {
                indexer.index(in);
            }
        }

        // 3. CDI-related annotations (qualifiers, interceptor bindings, stereotypes)
        // CDI recognizes them even if they come from an archive that is not a bean archive
        Set<Class<? extends Annotation>> metaAnnotations = Set.of(Qualifier.class, InterceptorBinding.class, Stereotype.class);
        for (Class<? extends Annotation> metaAnnotation : metaAnnotations) {
            DotName metaAnnotationName = DotName.createSimple(metaAnnotation.getName());
            for (AnnotationInstance annotation : applicationIndex.getAnnotations(metaAnnotationName)) {
                if (annotation.target().kind().equals(AnnotationTarget.Kind.CLASS)) {
                    String annotationClass = annotation.target().asClass().name().toString();
                    String classFile = annotationClass.replace('.', '/') + ".class";
                    if (beanArchivePaths.contains(classFile)) {
                        continue;
                    }
                    try (InputStream in = Files.newInputStream(deploymentDir.appClasses.resolve(classFile))) {
                        indexer.index(in);
                    }
                }
            }
        }

        return BeanArchives.buildImmutableBeanArchiveIndex(indexer.complete());
    }

    private Closeable withDeploymentClassLoader() throws IOException {
        ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
        DeploymentClassLoader newCl = new DeploymentClassLoader(deploymentDir);

        Thread.currentThread().setContextClassLoader(newCl);
        return new Closeable() {
            @Override
            public void close() throws IOException {
                Thread.currentThread().setContextClassLoader(oldCl);
                newCl.close();
            }
        };
    }
}
