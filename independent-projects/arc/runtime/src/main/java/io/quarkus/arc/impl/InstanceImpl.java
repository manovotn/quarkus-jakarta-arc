package io.quarkus.arc.impl;

import static io.quarkus.arc.impl.TypeCachePollutionUtils.asParameterizedType;
import static io.quarkus.arc.impl.TypeCachePollutionUtils.isParameterizedType;

import java.lang.annotation.Annotation;
import java.lang.reflect.Member;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ClientProxy;
import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.InjectableContext;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.arc.WithCaching;
import io.quarkus.arc.impl.CurrentInjectionPointProvider.InjectionPointImpl;

/**
 *
 * @author Martin Kouba
 */
public class InstanceImpl<T> implements InjectableInstance<T> {

    public static Instance<Object> forSynthesis(CreationalContextImpl<?> creationalContext, boolean allowInjectionPointLookup) {
        InstanceImpl<Object> result = new InstanceImpl<>(null, null, Object.class, Collections.emptySet(),
                creationalContext, Collections.emptySet(), null, -1, false);
        if (allowInjectionPointLookup) {
            return result;
        }

        class Guard<T> implements InjectableInstance<T> {
            private final InjectableInstance<T> delegate;

            Guard(InjectableInstance<T> delegate) {
                this.delegate = delegate;
            }

            @Override
            public InstanceHandle<T> getHandle() {
                return delegate.getHandle();
            }

            @Override
            public Iterable<InstanceHandle<T>> handles() {
                return delegate.handles();
            }

            @Override
            public InjectableInstance<T> select(Annotation... qualifiers) {
                return wrap(null, delegate.select(qualifiers));
            }

            @Override
            public <U extends T> InjectableInstance<U> select(Class<U> subtype, Annotation... qualifiers) {
                return wrap(subtype, delegate.select(subtype, qualifiers));
            }

            @Override
            public <U extends T> InjectableInstance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
                return wrap(subtype.getType(), delegate.select(subtype, qualifiers));
            }

            private <U> InjectableInstance<U> wrap(Type subtype, InjectableInstance<U> delegate) {
                if (InjectionPoint.class.equals(subtype)) {
                    throw new IllegalStateException("Cannot obtain InjectionPoint metadata for non-@Dependent bean");
                }
                return new Guard<>(delegate);
            }

            @Override
            public void clearCache() {
                delegate.clearCache();
            }

            @Override
            public Iterator<T> iterator() {
                return delegate.iterator();
            }

            @Override
            public boolean isUnsatisfied() {
                return delegate.isUnsatisfied();
            }

            @Override
            public boolean isAmbiguous() {
                return delegate.isAmbiguous();
            }

            @Override
            public void destroy(T instance) {
                delegate.destroy(instance);
            }

            @Override
            public T get() {
                return delegate.get();
            }
        }
        return new Guard<>(result);
    }

    static <T> InstanceImpl<T> of(Type requiredType, Set<Annotation> requiredQualifiers) {
        return new InstanceImpl<>(null, null, requiredType, requiredQualifiers,
                new CreationalContextImpl<>(null),
                Collections.emptySet(), null, -1, true);
    }

    private static <T> InstanceImpl<T> child(InstanceImpl<?> parent, Type requiredType, Set<Annotation> requiredQualifiers) {
        return new InstanceImpl<>(parent.targetBean, parent.injectionPointType, requiredType, requiredQualifiers,
                parent.creationalContext, parent.annotations, parent.javaMember, parent.position,
                parent.resetCurrentInjectionPoint);
    }

    private final CreationalContextImpl<?> creationalContext;
    private final List<InjectableBean<?>> resolvedBeans;

    private final Type requiredType;
    private final Set<Annotation> requiredQualifiers;

    // The following fields are only needed for InjectionPoint metadata
    private final Type injectionPointType;
    private final InjectableBean<?> targetBean;
    private final Set<Annotation> annotations;
    private final Member javaMember;
    private final int position;
    private final boolean resetCurrentInjectionPoint;

    private final LazyValue<T> cachedGetResult;

    InstanceImpl(InjectableBean<?> targetBean, Type type, Set<Annotation> qualifiers,
            CreationalContextImpl<?> creationalContext, Set<Annotation> annotations, Member javaMember, int position) {
        this(targetBean, type, getRequiredType(type), qualifiers, creationalContext, annotations, javaMember, position, true);
    }

    private InstanceImpl(InjectableBean<?> targetBean, Type injectionPointType, Type requiredType,
            Set<Annotation> requiredQualifiers, CreationalContextImpl<?> creationalContext, Set<Annotation> annotations,
            Member javaMember, int position, boolean resetCurrentInjectionPoint) {
        this.injectionPointType = injectionPointType;
        this.requiredType = requiredType;
        this.requiredQualifiers = requiredQualifiers != null ? requiredQualifiers : Collections.emptySet();
        this.creationalContext = creationalContext;
        if (this.requiredQualifiers.isEmpty() && Object.class.equals(requiredType)) {
            // Do not prefetch the beans for Instance<Object> with no qualifiers
            this.resolvedBeans = null;
        } else {
            this.resolvedBeans = resolve();
        }
        this.targetBean = targetBean;
        this.annotations = annotations;
        this.javaMember = javaMember;
        this.position = position;
        this.resetCurrentInjectionPoint = resetCurrentInjectionPoint;
        this.cachedGetResult = isGetCached(annotations) ? new LazyValue<>(this::getInternal) : null;
    }

    @Override
    public Iterator<T> iterator() {
        return new InstanceIterator(beans());
    }

    @Override
    public T get() {
        return cachedGetResult != null ? cachedGetResult.get() : getInternal();
    }

    @Override
    public InjectableInstance<T> select(Annotation... qualifiers) {
        Set<Annotation> newQualifiers = new HashSet<>(this.requiredQualifiers);
        Collections.addAll(newQualifiers, qualifiers);
        return InstanceImpl.child(this, requiredType, newQualifiers);
    }

    @Override
    public <U extends T> InjectableInstance<U> select(Class<U> subtype, Annotation... qualifiers) {
        Set<Annotation> newQualifiers = new HashSet<>(this.requiredQualifiers);
        Collections.addAll(newQualifiers, qualifiers);
        return InstanceImpl.child(this, subtype, newQualifiers);
    }

    @Override
    public <U extends T> InjectableInstance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        Set<Annotation> newQualifiers = new HashSet<>(this.requiredQualifiers);
        Collections.addAll(newQualifiers, qualifiers);
        return InstanceImpl.child(this, subtype.getType(), newQualifiers);
    }

    @Override
    public boolean isUnsatisfied() {
        return beans().isEmpty();
    }

    @Override
    public boolean isAmbiguous() {
        return beans().size() > 1;
    }

    @Override
    public void destroy(Object instance) {
        Objects.requireNonNull(instance);
        if (instance instanceof ClientProxy) {
            ClientProxy proxy = (ClientProxy) instance;
            InjectableContext context = Arc.container().getActiveContext(proxy.arc_bean().getScope());
            if (context == null) {
                throw new ContextNotActiveException("No active context found for: " + proxy.arc_bean().getScope());
            }
            context.destroy(proxy.arc_bean());
        } else {
            // First try to destroy a dependent instance
            if (!creationalContext.removeDependentInstance(instance, true)) {
                // If not successful then try the singleton context
                SingletonContext singletonContext = (SingletonContext) Arc.container().getActiveContext(Singleton.class);
                singletonContext.destroyInstance(instance);
            }
        }
    }

    @Override
    public InstanceHandle<T> getHandle() {
        return getHandle(bean());
    }

    @Override
    public Iterable<InstanceHandle<T>> handles() {
        return new Iterable<InstanceHandle<T>>() {
            @Override
            public Iterator<InstanceHandle<T>> iterator() {
                return new InstanceHandlesIterator<T>(beans());
            }
        };
    }

    private <H> InstanceHandle<H> getHandle(InjectableBean<H> bean) {
        CreationalContextImpl<H> context = this.creationalContext.child(bean);
        return new LazyInstanceHandle<>(bean, context, this.creationalContext, new Supplier<H>() {

            @Override
            public H get() {
                InjectionPoint prev = null;
                if (resetCurrentInjectionPoint) {
                    prev = InjectionPointProvider.set(new InjectionPointImpl(injectionPointType, requiredType,
                            requiredQualifiers, targetBean, annotations, javaMember, position));
                }
                try {
                    return bean.get(context);
                } finally {
                    if (resetCurrentInjectionPoint) {
                        InjectionPointProvider.set(prev);
                    }
                }
            }
        },
                // If @Dependent we need to remove the instance from the CC of this InjectableInstance
                Dependent.class.equals(bean.getScope()) ? this::destroy : null);
    }

    @SuppressWarnings("unchecked")
    private InjectableBean<T> bean() {
        List<InjectableBean<?>> beans = beans();
        if (beans.isEmpty()) {
            ArcContainerImpl.instance().scanRemovedBeans(requiredType, requiredQualifiers.toArray(new Annotation[] {}));
            throw new UnsatisfiedResolutionException(
                    "No bean found for required type [" + requiredType + "] and qualifiers [" + requiredQualifiers + "]");
        } else if (beans.size() > 1) {
            throw new AmbiguousResolutionException("Beans: " + beans.toString());
        }
        return (InjectableBean<T>) beans.iterator().next();
    }

    public boolean hasDependentInstances() {
        return creationalContext.hasDependentInstances();
    }

    @Override
    public void clearCache() {
        if (cachedGetResult.isSet()) {
            creationalContext.release();
            cachedGetResult.clear();
        }
    }

    private T getInternal() {
        return getBeanInstance(bean());
    }

    void destroy() {
        creationalContext.release();
    }

    private T getBeanInstance(InjectableBean<T> bean) {
        CreationalContextImpl<T> ctx = creationalContext.child(bean);
        InjectionPoint prev = null;
        if (resetCurrentInjectionPoint) {
            prev = InjectionPointProvider.set(new InjectionPointImpl(injectionPointType, requiredType,
                    requiredQualifiers, targetBean, annotations, javaMember, position));
        }
        T instance;
        try {
            instance = bean.get(ctx);
        } finally {
            if (resetCurrentInjectionPoint) {
                InjectionPointProvider.set(prev);
            }
        }
        return instance;
    }

    private List<InjectableBean<?>> beans() {
        return resolvedBeans != null ? resolvedBeans : resolve();
    }

    private List<InjectableBean<?>> resolve() {
        return Instances.resolveBeans(requiredType, requiredQualifiers);
    }

    class InstanceIterator implements Iterator<T> {

        protected final Iterator<InjectableBean<?>> delegate;

        private InstanceIterator(Collection<InjectableBean<?>> beans) {
            this.delegate = beans.iterator();
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @SuppressWarnings("unchecked")
        @Override
        public T next() {
            return getBeanInstance((InjectableBean<T>) delegate.next());
        }

    }

    private class InstanceHandlesIterator<H> implements Iterator<InstanceHandle<H>> {

        final Iterator<InjectableBean<?>> delegate;

        InstanceHandlesIterator(Collection<InjectableBean<?>> beans) {
            this.delegate = beans.iterator();
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @SuppressWarnings("unchecked")
        @Override
        public InstanceHandle<H> next() {
            return getHandle((InjectableBean<H>) delegate.next());
        }

    }

    private static Type getRequiredType(final Type type) {
        if (isParameterizedType(type)) {
            final ParameterizedType parameterizedType = asParameterizedType(type);
            if (Provider.class.isAssignableFrom(Types.getRawType(parameterizedType.getRawType()))) {
                return parameterizedType.getActualTypeArguments()[0];
            }
        }
        throw new IllegalArgumentException("Not a valid type: " + type);
    }

    private boolean isGetCached(Set<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().equals(WithCaching.class)) {
                return true;
            }
        }
        return false;
    }

}
