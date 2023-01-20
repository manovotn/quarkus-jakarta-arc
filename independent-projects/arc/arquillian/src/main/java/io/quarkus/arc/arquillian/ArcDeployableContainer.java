package io.quarkus.arc.arquillian;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.context.annotation.DeploymentScoped;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.arc.InstanceHandle;

public class ArcDeployableContainer implements DeployableContainer<ArcContainerConfiguration> {
    @Inject
    @DeploymentScoped
    private InstanceProducer<DeploymentDir> deploymentDir;

    @Inject
    @DeploymentScoped
    private InstanceProducer<ClassLoader> deploymentClassLoader;

    @Inject
    @DeploymentScoped
    private InstanceProducer<ArcContainer> runningArc;

    @Inject
    private Instance<TestClass> testClass;

    static Object testInstance;

    @Override
    public Class<ArcContainerConfiguration> getConfigurationClass() {
        return ArcContainerConfiguration.class;
    }

    @Override
    public void setup(ArcContainerConfiguration configuration) {
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("ArC");
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        if (testClass.get() == null) {
            throw new IllegalStateException("Test class not available");
        }
        String testClassName = testClass.get().getName();

        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try {
            DeploymentDir deploymentDir = new DeploymentDir();
            this.deploymentDir.set(deploymentDir);

            DeploymentClassLoader deploymentClassLoader = new Deployer(archive, deploymentDir, testClassName).deploy();
            this.deploymentClassLoader.set(deploymentClassLoader);

            Thread.currentThread().setContextClassLoader(deploymentClassLoader);

            ArcContainer arcContainer = Arc.initialize();
            runningArc.set(arcContainer);

            Class<?> actualTestClass = Class.forName(testClassName, true, deploymentClassLoader);
            testInstance = findTest(arcContainer, actualTestClass).get();
        } catch (Throwable t) {
            // clone the exception into the correct class loader
            Throwable nt;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ObjectOutputStream a = new ObjectOutputStream(out)) {
                a.writeObject(t);
                a.close();
                nt = (Throwable) new ObjectInputStream(new ByteArrayInputStream(out.toByteArray())).readObject();
            } catch (Exception e) {
                throw new DeploymentException("Unable to start ArC", t);
            }
            throw new DeploymentException("Unable to start ArC", nt);
        } finally {
            Thread.currentThread().setContextClassLoader(old);
        }

        return new ProtocolMetaData();
    }

    private InstanceHandle<?> findTest(ArcContainer arc, Class<?> testClass) {
        InjectableInstance<?> instance = arc.select(testClass);
        if (instance.isResolvable()) {
            return instance.getHandle();
        }

        // fallback for generic test classes, whose set of bean types does not contain a `Class`
        // but a `ParameterizedType` instead
        for (InstanceHandle<Object> handle : arc.listAll(Object.class)) {
            if (testClass.equals(handle.getBean().getBeanClass())) {
                return handle;
            }
        }

        throw new IllegalStateException("No bean: " + testClass);
    }

    @Override
    public void undeploy(Archive<?> archive) {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try {
            ArcContainer arcContainer = runningArc.get();
            if (arcContainer != null) {
                Thread.currentThread().setContextClassLoader(deploymentClassLoader.get());
                Arc.shutdown();
            }
            testInstance = null;

            DeploymentDir deploymentDir = this.deploymentDir.get();
            if (deploymentDir != null) {
                if (System.getProperty("retainDeployment") == null) {
                    deleteDirectory(deploymentDir.root);
                } else {
                    System.out.println("Deployment for test " + testClass.get().getName()
                            + " retained in: " + deploymentDir.root);
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    private static void deleteDirectory(Path dir) {
        try {
            Files.walkFileTree(dir, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void deploy(Descriptor descriptor) {
        throw new UnsupportedOperationException();

    }

    @Override
    public void undeploy(Descriptor descriptor) {
        throw new UnsupportedOperationException();

    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }
}
