package io.quarkus.arc.processor.cdi.build.compatible.extensions;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanDisposer;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.spi.EventContext;
import jakarta.enterprise.util.Nonbinding;

import org.jboss.jandex.DotName;

import io.quarkus.arc.InjectableContext;
import io.quarkus.arc.impl.CreationalContextImpl;
import io.quarkus.arc.impl.InstanceImpl;
import io.quarkus.arc.processor.BeanConfigurator;
import io.quarkus.arc.processor.BeanDeploymentValidator;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.arc.processor.BeanProcessor;
import io.quarkus.arc.processor.BeanRegistrar;
import io.quarkus.arc.processor.BuildExtension;
import io.quarkus.arc.processor.ConfiguratorBase;
import io.quarkus.arc.processor.ContextConfigurator;
import io.quarkus.arc.processor.ContextRegistrar;
import io.quarkus.arc.processor.InterceptorBindingRegistrar;
import io.quarkus.arc.processor.ObserverConfigurator;
import io.quarkus.arc.processor.ObserverInfo;
import io.quarkus.arc.processor.ObserverRegistrar;
import io.quarkus.arc.processor.QualifierRegistrar;
import io.quarkus.arc.processor.StereotypeInfo;
import io.quarkus.arc.processor.StereotypeRegistrar;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;

public class ExtensionsEntryPoint {
    private final ExtensionInvoker invoker = new ExtensionInvoker();
    private final AllAnnotationOverlays annotationOverlays = new AllAnnotationOverlays();
    private final MessagesImpl messages = new MessagesImpl();

    private final Map<DotName, ClassConfig> qualifiers = new HashMap<>();
    private final Map<DotName, ClassConfig> interceptorBindings = new HashMap<>();
    private final Map<DotName, ClassConfig> stereotypes = new HashMap<>();
    private final List<MetaAnnotationsImpl.ContextData> contexts = new ArrayList<>();

    private AllAnnotationTransformations preAnnotationTransformations;

    private final List<SyntheticBeanBuilderImpl<?>> syntheticBeans = new ArrayList<>();
    private final List<SyntheticObserverBuilderImpl<?>> syntheticObservers = new ArrayList<>();

    /**
     * Must be called first, <i>before</i> {@code registerMetaAnnotations}.
     */
    public void runDiscovery(org.jboss.jandex.IndexView applicationIndex, Set<String> additionalClasses) {
        try {
            BuildServicesImpl.init(applicationIndex, annotationOverlays);

            this.preAnnotationTransformations = new AllAnnotationTransformations(applicationIndex, annotationOverlays);

            new ExtensionPhaseDiscovery(invoker, applicationIndex, messages, additionalClasses,
                    preAnnotationTransformations, qualifiers, interceptorBindings, stereotypes, contexts).run();
        } finally {
            preAnnotationTransformations.freeze();

            BuildServicesImpl.reset();
        }
    }

    /**
     * Must be called <i>after</i> {@code runDiscovery} and <i>before</i> {@code runEnhancement}.
     */
    public void registerMetaAnnotations(BeanProcessor.Builder builder) {
        builder.addAnnotationTransformer(preAnnotationTransformations.classes);
        builder.addAnnotationTransformer(preAnnotationTransformations.methods);
        builder.addAnnotationTransformer(preAnnotationTransformations.parameters);
        builder.addAnnotationTransformer(preAnnotationTransformations.fields);

        if (!qualifiers.isEmpty()) {
            builder.addQualifierRegistrar(new QualifierRegistrar() {
                @Override
                public Map<DotName, Set<String>> getAdditionalQualifiers() {
                    Map<DotName, Set<String>> result = new HashMap<>();
                    for (Map.Entry<DotName, ClassConfig> entry : qualifiers.entrySet()) {
                        DotName annotationName = entry.getKey();
                        ClassConfig config = entry.getValue();

                        Set<String> nonbindingMembers = config.methods()
                                .stream()
                                .filter(it -> it.info().hasAnnotation(Nonbinding.class))
                                .map(it -> it.info().name())
                                .collect(Collectors.toUnmodifiableSet());
                        result.put(annotationName, nonbindingMembers);
                    }
                    return result;
                }
            });
        }

        if (!interceptorBindings.isEmpty()) {
            builder.addInterceptorBindingRegistrar(new InterceptorBindingRegistrar() {
                @Override
                public List<InterceptorBinding> getAdditionalBindings() {
                    return interceptorBindings.entrySet()
                            .stream()
                            .map(entry -> {
                                DotName annotationName = entry.getKey();
                                ClassConfig config = entry.getValue();

                                Set<String> nonbindingMembers = config.methods()
                                        .stream()
                                        .filter(it -> it.info().hasAnnotation(Nonbinding.class))
                                        .map(it -> it.info().name())
                                        .collect(Collectors.toUnmodifiableSet());

                                return InterceptorBinding.of(annotationName, nonbindingMembers);
                            })
                            .collect(Collectors.toUnmodifiableList());
                }
            });
        }

        if (!stereotypes.isEmpty()) {
            builder.addStereotypeRegistrar(new StereotypeRegistrar() {
                @Override
                public Set<DotName> getAdditionalStereotypes() {
                    return stereotypes.keySet();
                }
            });
        }

        if (!contexts.isEmpty()) {
            for (MetaAnnotationsImpl.ContextData context : contexts) {
                builder.addContextRegistrar(new ContextRegistrar() {
                    @Override
                    public void register(RegistrationContext registrationContext) {
                        Class<? extends Annotation> scopeAnnotation = context.scopeAnnotation;
                        // TODO TODO TODO AlterableContext!!! how many changes in ArC will be needed?
                        Class<? extends InjectableContext> contextClass = (Class) context.contextClass;

                        ContextConfigurator config = registrationContext.configure(scopeAnnotation)
                                .contextClass(contextClass);
                        if (context.isNormal != null) {
                            config.normal(context.isNormal);
                        }
                        config.done();
                    }
                });
            }
        }
    }

    /**
     * Must be called <i>after</i> {@code registerMetaAnnotations} and <i>before</i> {@code runRegistration}.
     */
    public void runEnhancement(org.jboss.jandex.IndexView beanArchiveIndex, BeanProcessor.Builder builder) {
        AllAnnotationTransformations annotationTransformations = new AllAnnotationTransformations(beanArchiveIndex,
                annotationOverlays);
        builder.addAnnotationTransformer(annotationTransformations.classes);
        builder.addAnnotationTransformer(annotationTransformations.methods);
        builder.addAnnotationTransformer(annotationTransformations.parameters);
        builder.addAnnotationTransformer(annotationTransformations.fields);

        BuildServicesImpl.init(beanArchiveIndex, annotationOverlays);

        try {
            new ExtensionPhaseEnhancement(invoker, beanArchiveIndex, messages, annotationTransformations).run();
        } finally {
            annotationTransformations.freeze();
        }
    }

    /**
     * Must be called <i>after</i> {@code runEnhancement} and <i>before</i> {@code runSynthesis}.
     */
    public void runRegistration(org.jboss.jandex.IndexView beanArchiveIndex,
            Collection<io.quarkus.arc.processor.BeanInfo> allBeans,
            Collection<io.quarkus.arc.processor.ObserverInfo> allObservers) {

        new ExtensionPhaseRegistration(invoker, beanArchiveIndex, messages, annotationOverlays, allBeans, allObservers).run();
    }

    /**
     * Must be called <i>after</i> {@code runRegistration} and <i>before</i> {@code registerSyntheticBeans}.
     */
    public void runSynthesis(org.jboss.jandex.IndexView beanArchiveIndex) {
        new ExtensionPhaseSynthesis(invoker, beanArchiveIndex, messages, annotationOverlays, syntheticBeans,
                syntheticObservers).run();
    }

    /**
     * Must be called <i>after</i> {@code runSynthesis} and <i>before</i> {@code runRegistrationAgain}.
     */
    public void registerSyntheticBeans(BeanRegistrar.RegistrationContext context) {
        Map<DotName, StereotypeInfo> registeredStereotypes = context.get(BuildExtension.Key.STEREOTYPES);

        for (SyntheticBeanBuilderImpl<?> syntheticBean : syntheticBeans) {
            StereotypeInfo[] stereotypes = syntheticBean.stereotypes.stream()
                    .map(registeredStereotypes::get)
                    .toArray(StereotypeInfo[]::new);

            BeanConfigurator<Object> bean = context.configure(syntheticBean.implementationClass)
                    .types(syntheticBean.types.toArray(new org.jboss.jandex.Type[0]))
                    .qualifiers(syntheticBean.qualifiers.toArray(new org.jboss.jandex.AnnotationInstance[0]))
                    .stereotypes(stereotypes);
            if (syntheticBean.scope != null) {
                bean.scope(syntheticBean.scope);
            }
            if (syntheticBean.name != null) {
                bean.name(syntheticBean.name);
            }
            if (syntheticBean.isAlternative) {
                bean.alternativePriority(syntheticBean.priority);
            }
            configureParams(bean, syntheticBean.params);
            // TODO can't really know if the scope is @Dependent, because there may be a stereotype with default scope
            //  but this will have to do for now
            boolean isDependent = syntheticBean.scope == null || Dependent.class.equals(syntheticBean.scope);
            bean.creator(mc -> { // generated method signature: Object(CreationalContext)
                // | CreationalContextImpl creationalContextImpl = (CreationalContextImpl) creationalContext;
                ResultHandle creationalContextImpl = mc.checkCast(mc.getMethodParam(0), CreationalContextImpl.class);
                // | Instance<Object> lookup = InstanceImpl.forSynthesis(creationalContext, isDependent);
                ResultHandle lookup = mc.invokeStaticMethod(MethodDescriptor.ofMethod(InstanceImpl.class,
                        "forSynthesis", Instance.class, CreationalContextImpl.class, boolean.class),
                        creationalContextImpl, mc.load(isDependent));

                // | Map<String, Object> paramsMap = this.params;
                // the generated bean class has a "params" field filled with all the data
                ResultHandle paramsMap = mc.readInstanceField(
                        FieldDescriptor.of(mc.getMethodDescriptor().getDeclaringClass(), "params", Map.class),
                        mc.getThis());
                // | Parameters params = new ParametersImpl(paramsMap);
                ResultHandle params = mc.newInstance(MethodDescriptor.ofConstructor(ParametersImpl.class, Map.class),
                        paramsMap);

                // | SyntheticBeanCreator creator = new ConfiguredSyntheticBeanCreator();
                ResultHandle creator = mc.newInstance(MethodDescriptor.ofConstructor(syntheticBean.creatorClass));

                // | Object instance = creator.create(lookup, params);
                ResultHandle[] args = { lookup, params };
                ResultHandle instance = mc.invokeInterfaceMethod(MethodDescriptor.ofMethod(SyntheticBeanCreator.class,
                        "create", Object.class, Instance.class, Parameters.class), creator, args);

                // | return instance;
                mc.returnValue(instance);
            });
            if (syntheticBean.disposerClass != null) {
                bean.destroyer(mc -> { // generated method signature: void(Object, CreationalContext)
                    // | CreationalContextImpl creationalContextImpl = (CreationalContextImpl) creationalContext;
                    ResultHandle creationalContextImpl = mc.checkCast(mc.getMethodParam(1), CreationalContextImpl.class);
                    // | Instance<Object> lookup = InstanceImpl.forSynthesis(creationalContext, isDependent);
                    ResultHandle lookup = mc.invokeStaticMethod(MethodDescriptor.ofMethod(InstanceImpl.class,
                            "forSynthesis", Instance.class, CreationalContextImpl.class, boolean.class),
                            creationalContextImpl, mc.load(false)); // looking up InjectionPoint in disposer is invalid

                    // | Map<String, Object> paramsMap = this.params;
                    // the generated bean class has a "params" field filled with all the data
                    ResultHandle paramsMap = mc.readInstanceField(
                            FieldDescriptor.of(mc.getMethodDescriptor().getDeclaringClass(), "params", Map.class),
                            mc.getThis());
                    // | Parameters params = new ParametersImpl(paramsMap);
                    ResultHandle params = mc.newInstance(MethodDescriptor.ofConstructor(ParametersImpl.class, Map.class),
                            paramsMap);

                    // | SyntheticBeanDisposer disposer = new ConfiguredSyntheticBeanDisposer();
                    ResultHandle disposer = mc.newInstance(MethodDescriptor.ofConstructor(syntheticBean.disposerClass));

                    // | disposer.dispose(instance, lookup, params);
                    ResultHandle[] args = { mc.getMethodParam(0), lookup, params };
                    mc.invokeInterfaceMethod(MethodDescriptor.ofMethod(SyntheticBeanDisposer.class, "dispose",
                            void.class, Object.class, Instance.class, Parameters.class), disposer, args);

                    // | creationalContextImpl.release()
                    mc.invokeVirtualMethod(MethodDescriptor.ofMethod(CreationalContextImpl.class, "release", void.class),
                            creationalContextImpl);

                    // return type is void
                    mc.returnValue(null);
                });
            }
            bean.done();
        }
    }

    /**
     * Must be called <i>after</i> {@code runSynthesis} and <i>before</i> {@code runRegistrationAgain}.
     */
    public void registerSyntheticObservers(ObserverRegistrar.RegistrationContext context) {
        for (SyntheticObserverBuilderImpl<?> syntheticObserver : syntheticObservers) {
            if (syntheticObserver.isAsync && syntheticObserver.transactionPhase != TransactionPhase.IN_PROGRESS) {
                throw new IllegalStateException("Synthetic observer declared as asynchronous and transactional "
                        + "(event type " + syntheticObserver.type + ", \"declared\" by " + syntheticObserver.declaringClass
                        + ", notified using " + syntheticObserver.implementationClass + ")");
            }

            ObserverConfigurator observer = context.configure()
                    .beanClass(syntheticObserver.declaringClass)
                    .observedType(syntheticObserver.type)
                    .qualifiers(syntheticObserver.qualifiers.toArray(new org.jboss.jandex.AnnotationInstance[0]))
                    .priority(syntheticObserver.priority)
                    .async(syntheticObserver.isAsync)
                    .transactionPhase(syntheticObserver.transactionPhase);
            configureParams(observer, syntheticObserver.params);
            observer.notify(mc -> { // generated method signature: void(EventContext)
                // | SyntheticObserver instance = new ConfiguredEventConsumer();
                ResultHandle instance = mc.newInstance(MethodDescriptor.ofConstructor(syntheticObserver.implementationClass));

                // | Map<String, Object> paramsMap = this.params;
                // the generated observer class has a "params" field filled with all the data
                ResultHandle paramsMap = mc.readInstanceField(
                        FieldDescriptor.of(mc.getMethodDescriptor().getDeclaringClass(), "params", Map.class),
                        mc.getThis());

                // | Parameters params = new ParametersImpl(paramsMap);
                ResultHandle params = mc.newInstance(MethodDescriptor.ofConstructor(ParametersImpl.class, Map.class),
                        paramsMap);

                // | instance.observe(eventContext, params);
                ResultHandle[] args = { mc.getMethodParam(0), params };
                mc.invokeInterfaceMethod(MethodDescriptor.ofMethod(SyntheticObserver.class, "observe",
                        void.class, EventContext.class, Parameters.class), instance, args);

                // return type is void
                mc.returnValue(null);
            });
            observer.done();
        }
    }

    private void configureParams(ConfiguratorBase<?> configurator, Map<String, Object> params) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() instanceof Boolean) {
                configurator.param(entry.getKey(), (Boolean) entry.getValue());
            } else if (entry.getValue() instanceof boolean[]) {
                configurator.param(entry.getKey(), (boolean[]) entry.getValue());
            } else if (entry.getValue() instanceof Byte) {
                configurator.param(entry.getKey(), (Byte) entry.getValue());
            } else if (entry.getValue() instanceof byte[]) {
                configurator.param(entry.getKey(), (byte[]) entry.getValue());
            } else if (entry.getValue() instanceof Short) {
                configurator.param(entry.getKey(), (Short) entry.getValue());
            } else if (entry.getValue() instanceof short[]) {
                configurator.param(entry.getKey(), (short[]) entry.getValue());
            } else if (entry.getValue() instanceof Integer) {
                configurator.param(entry.getKey(), (Integer) entry.getValue());
            } else if (entry.getValue() instanceof int[]) {
                configurator.param(entry.getKey(), (int[]) entry.getValue());
            } else if (entry.getValue() instanceof Long) {
                configurator.param(entry.getKey(), (Long) entry.getValue());
            } else if (entry.getValue() instanceof long[]) {
                configurator.param(entry.getKey(), (long[]) entry.getValue());
            } else if (entry.getValue() instanceof Float) {
                configurator.param(entry.getKey(), (Float) entry.getValue());
            } else if (entry.getValue() instanceof float[]) {
                configurator.param(entry.getKey(), (float[]) entry.getValue());
            } else if (entry.getValue() instanceof Double) {
                configurator.param(entry.getKey(), (Double) entry.getValue());
            } else if (entry.getValue() instanceof double[]) {
                configurator.param(entry.getKey(), (double[]) entry.getValue());
            } else if (entry.getValue() instanceof Character) {
                configurator.param(entry.getKey(), (Character) entry.getValue());
            } else if (entry.getValue() instanceof char[]) {
                configurator.param(entry.getKey(), (char[]) entry.getValue());
            } else if (entry.getValue() instanceof String) {
                configurator.param(entry.getKey(), (String) entry.getValue());
            } else if (entry.getValue() instanceof String[]) {
                configurator.param(entry.getKey(), (String[]) entry.getValue());
            } else if (entry.getValue() instanceof Enum<?>) {
                configurator.param(entry.getKey(), (Enum<?>) entry.getValue());
            } else if (entry.getValue() instanceof Enum<?>[]) {
                configurator.param(entry.getKey(), (Enum<?>[]) entry.getValue());
            } else if (entry.getValue() instanceof Class<?>) {
                configurator.param(entry.getKey(), (Class<?>) entry.getValue());
            } else if (entry.getValue() instanceof Class<?>[]) {
                configurator.param(entry.getKey(), (Class<?>[]) entry.getValue());
            } else if (entry.getValue() instanceof org.jboss.jandex.ClassInfo) {
                configurator.param(entry.getKey(), (org.jboss.jandex.ClassInfo) entry.getValue());
            } else if (entry.getValue() instanceof org.jboss.jandex.ClassInfo[]) {
                configurator.param(entry.getKey(), (org.jboss.jandex.ClassInfo[]) entry.getValue());
            } else if (entry.getValue() instanceof org.jboss.jandex.AnnotationInstance) {
                configurator.param(entry.getKey(), (org.jboss.jandex.AnnotationInstance) entry.getValue());
            } else if (entry.getValue() instanceof org.jboss.jandex.AnnotationInstance[]) {
                configurator.param(entry.getKey(), (org.jboss.jandex.AnnotationInstance[]) entry.getValue());
            } else {
                throw new IllegalStateException("Unknown param: " + entry);
            }
        }
    }

    /**
     * Must be called <i>after</i> {@code registerSynthetic{Beans,Observers}} and <i>before</i>
     * {@code runValidation}.
     */
    public void runRegistrationAgain(org.jboss.jandex.IndexView beanArchiveIndex,
            Collection<io.quarkus.arc.processor.BeanInfo> allBeans,
            Collection<io.quarkus.arc.processor.ObserverInfo> allObservers) {
        Collection<io.quarkus.arc.processor.BeanInfo> syntheticBeans = allBeans.stream()
                .filter(BeanInfo::isSynthetic)
                .collect(Collectors.toUnmodifiableList());
        Collection<io.quarkus.arc.processor.ObserverInfo> syntheticObservers = allObservers.stream()
                .filter(ObserverInfo::isSynthetic)
                .collect(Collectors.toUnmodifiableList());
        new ExtensionPhaseRegistration(invoker, beanArchiveIndex, messages, annotationOverlays, syntheticBeans,
                syntheticObservers).run();
    }

    /**
     * Must be called <i>after</i> {@code runRegistrationAgain} and <i>before</i> {@code registerValidationErrors}.
     */
    public void runValidation(org.jboss.jandex.IndexView beanArchiveIndex,
            Collection<io.quarkus.arc.processor.BeanInfo> allBeans,
            Collection<io.quarkus.arc.processor.ObserverInfo> allObservers) {
        new ExtensionPhaseValidation(invoker, beanArchiveIndex, messages, annotationOverlays, allBeans, allObservers).run();
    }

    /**
     * Must be called last, <i>after</i> {@code runValidation}.
     */
    public void registerValidationErrors(BeanDeploymentValidator.ValidationContext context) {
        for (Throwable error : messages.errors) {
            context.addDeploymentProblem(error);
        }

        BuildServicesImpl.reset();

        // at the very end
        annotationOverlays.invalidate();
    }
}
