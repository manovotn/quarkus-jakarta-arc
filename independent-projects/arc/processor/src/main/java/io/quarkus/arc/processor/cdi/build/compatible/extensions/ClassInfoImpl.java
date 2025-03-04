package io.quarkus.arc.processor.cdi.build.compatible.extensions;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.declarations.MethodInfo;
import jakarta.enterprise.lang.model.declarations.PackageInfo;
import jakarta.enterprise.lang.model.declarations.RecordComponentInfo;
import jakarta.enterprise.lang.model.types.Type;
import jakarta.enterprise.lang.model.types.TypeVariable;

import org.jboss.jandex.DotName;

class ClassInfoImpl extends DeclarationInfoImpl<org.jboss.jandex.ClassInfo> implements ClassInfo {
    // only for equals/hashCode
    private final DotName name;

    ClassInfoImpl(org.jboss.jandex.IndexView jandexIndex, AllAnnotationOverlays annotationOverlays,
            org.jboss.jandex.ClassInfo jandexDeclaration) {
        super(jandexIndex, annotationOverlays, jandexDeclaration);
        this.name = jandexDeclaration.name();
    }

    @Override
    public String name() {
        return jandexDeclaration.name().toString();
    }

    @Override
    public String simpleName() {
        return jandexDeclaration.simpleName();
    }

    @Override
    public PackageInfo packageInfo() {
        String packageName = jandexDeclaration.name().packagePrefix();
        org.jboss.jandex.ClassInfo packageClass = jandexIndex.getClassByName(
                DotName.createSimple(packageName + ".package-info"));
        return new PackageInfoImpl(jandexIndex, annotationOverlays, packageClass);
    }

    @Override
    public List<TypeVariable> typeParameters() {
        return jandexDeclaration.typeParameters()
                .stream()
                .map(it -> TypeImpl.fromJandexType(jandexIndex, annotationOverlays, it))
                .filter(Type::isTypeVariable) // not necessary, just as a precaution
                .map(Type::asTypeVariable) // not necessary, just as a precaution
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public Type superClass() {
        org.jboss.jandex.Type jandexSuperType = jandexDeclaration.superClassType();
        if (jandexSuperType == null) {
            return null;
        }
        return TypeImpl.fromJandexType(jandexIndex, annotationOverlays, jandexSuperType);
    }

    @Override
    public ClassInfo superClassDeclaration() {
        DotName jandexSuperType = jandexDeclaration.superName();
        if (jandexSuperType == null) {
            return null;
        }
        return new ClassInfoImpl(jandexIndex, annotationOverlays, jandexIndex.getClassByName(jandexSuperType));
    }

    @Override
    public List<Type> superInterfaces() {
        return jandexDeclaration.interfaceTypes()
                .stream()
                .map(it -> TypeImpl.fromJandexType(jandexIndex, annotationOverlays, it))
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public List<ClassInfo> superInterfacesDeclarations() {
        return jandexDeclaration.interfaceNames()
                .stream()
                .map(it -> new ClassInfoImpl(jandexIndex, annotationOverlays, jandexIndex.getClassByName(it)))
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public boolean isPlainClass() {
        // TODO there must be a better way
        return !isInterface() && !isEnum() && !isAnnotation() && !isRecord();
    }

    @Override
    public boolean isInterface() {
        if (isAnnotation()) {
            return false;
        }
        return Modifier.isInterface(jandexDeclaration.flags());
    }

    @Override
    public boolean isEnum() {
        return jandexDeclaration.isEnum();
    }

    @Override
    public boolean isAnnotation() {
        return jandexDeclaration.isAnnotation();
    }

    @Override
    public boolean isRecord() {
        return jandexDeclaration.isRecord();
    }

    @Override
    public boolean isAbstract() {
        return Modifier.isAbstract(jandexDeclaration.flags());
    }

    @Override
    public boolean isFinal() {
        return Modifier.isFinal(jandexDeclaration.flags());
    }

    @Override
    public int modifiers() {
        return jandexDeclaration.flags();
    }

    @Override
    public Collection<MethodInfo> constructors() {
        List<MethodInfo> result = new ArrayList<>();
        for (org.jboss.jandex.MethodInfo jandexMethod : jandexDeclaration.methods()) {
            if (jandexMethod.isSynthetic()) {
                continue;
            }
            if (MethodPredicates.IS_CONSTRUCTOR_JANDEX.test(jandexMethod)) {
                result.add(new MethodInfoImpl(jandexIndex, annotationOverlays, jandexMethod));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private void forEachSupertype(Consumer<org.jboss.jandex.ClassInfo> action) {
        // an interface may be inherited multiple times, but we only want to process it once
        Set<DotName> alreadyProcessed = new HashSet<>();
        Queue<org.jboss.jandex.ClassInfo> workQueue = new ArrayDeque<>();
        workQueue.add(jandexDeclaration);
        while (!workQueue.isEmpty()) {
            org.jboss.jandex.ClassInfo clazz = workQueue.remove();
            if (alreadyProcessed.contains(clazz.name())) {
                continue;
            }
            alreadyProcessed.add(clazz.name());

            DotName superClassName = clazz.superName();
            if (!DotNames.OBJECT.equals(superClassName)) {
                org.jboss.jandex.ClassInfo superClass = jandexIndex.getClassByName(superClassName);
                workQueue.add(superClass);
            }
            for (DotName superInterfaceName : clazz.interfaceNames()) {
                org.jboss.jandex.ClassInfo superInterface = jandexIndex.getClassByName(superInterfaceName);
                if (superInterface != null) {
                    // TODO e.g. java.lang.Annotation, in one of the tests, but isn't it a bug?
                    workQueue.add(superInterface);
                }
            }

            action.accept(clazz);
        }
    }

    //@formatter:off
/*
    @Override
    public Collection<MethodInfo> methods() {
        // TODO this is crazy inefficient

        class MethodDescriptor {
            org.jboss.jandex.ClassInfo declaringClass;
            org.jboss.jandex.Type returnType;
            String name;
            List<org.jboss.jandex.Type> parameterTypes;

            MethodDescriptor(org.jboss.jandex.MethodInfo jandexMethod) {
                this.declaringClass = jandexMethod.declaringClass();
                this.returnType = jandexMethod.returnType();
                this.name = jandexMethod.name();
                this.parameterTypes = jandexMethod.parameterTypes();
            }

            private boolean isDeclaredOnInterface() {
                return Modifier.isInterface(declaringClass.flags());
            }

            // returns whether A is a subtype of B
            // treats subtyping as reflexive, so X is a subtype of X
            private boolean isSubtypeOf(org.jboss.jandex.ClassInfo a, org.jboss.jandex.ClassInfo b) {
                if (a.name().equals(b.name())) {
                    return true;
                }

                DotName superClassName = a.superName();
                if (superClassName != null) {
                    org.jboss.jandex.ClassInfo aSuperClass = jandexIndex.getClassByName(superClassName);
                    if (aSuperClass != null && isSubtypeOf(aSuperClass, b)) {
                        return true;
                    }
                }

                for (DotName superInterfaceName : a.interfaceNames()) {
                    org.jboss.jandex.ClassInfo aSuperInterface = jandexIndex.getClassByName(superInterfaceName);
                    if (isSubtypeOf(aSuperInterface, b)) {
                        return true;
                    }
                }

                return false;
            }

            boolean hasSameSignatureAndReturnType(MethodDescriptor that) {
                return Objects.equals(this.name, that.name)
                        && Objects.equals(this.parameterTypes, that.parameterTypes)
                        && Objects.equals(this.returnType, that.returnType);
            }

            boolean isMoreSpecificThan(MethodDescriptor that) {
                if (this.isDeclaredOnInterface() && !that.isDeclaredOnInterface()) {
                    return false;
                }
                if (!this.isDeclaredOnInterface() && that.isDeclaredOnInterface()) {
                    return true;
                }
                return isSubtypeOf(this.declaringClass, that.declaringClass);
            }
        }

        Queue<org.jboss.jandex.MethodInfo> candidates = new ArrayDeque<>();

        forEachSupertype(clazz -> {
            for (org.jboss.jandex.MethodInfo jandexMethod : clazz.methods()) {
                if (MethodPredicates.IS_METHOD_JANDEX.test(jandexMethod)) {
                    candidates.add(jandexMethod);
                }
            }
        });

        List<MethodInfo> result = new ArrayList<>();

        for (org.jboss.jandex.MethodInfo jandexMethod : candidates) {
            MethodDescriptor methodDescriptor = new MethodDescriptor(jandexMethod);

            boolean moreSpecificExists = false;
            for (org.jboss.jandex.MethodInfo jandexOtherMethod : candidates) {
                if (jandexMethod == jandexOtherMethod) {
                    continue;
                }

                MethodDescriptor otherMethodDescriptor = new MethodDescriptor(jandexOtherMethod);

                if (otherMethodDescriptor.hasSameSignatureAndReturnType(methodDescriptor)
                        && otherMethodDescriptor.isMoreSpecificThan(methodDescriptor)) {
                    moreSpecificExists = true;
                    break;
                }
            }

            if (!moreSpecificExists) {
                result.add(new MethodInfoImpl(jandexIndex, annotationOverlays, jandexMethod));
            }
        }

        return Collections.unmodifiableList(result);
    }
*/
    //@formatter:on

    @Override
    public Collection<MethodInfo> methods() {
        List<MethodInfo> result = new ArrayList<>();
        forEachSupertype(clazz -> {
            for (org.jboss.jandex.MethodInfo jandexMethod : clazz.methods()) {
                if (jandexMethod.isSynthetic()) {
                    continue;
                }
                if (MethodPredicates.IS_METHOD_JANDEX.test(jandexMethod)) {
                    result.add(new MethodInfoImpl(jandexIndex, annotationOverlays, jandexMethod));
                }
            }
        });
        return Collections.unmodifiableList(result);
    }

    @Override
    public Collection<FieldInfo> fields() {
        List<FieldInfo> result = new ArrayList<>();
        forEachSupertype(clazz -> {
            for (org.jboss.jandex.FieldInfo jandexField : clazz.fields()) {
                if (jandexField.isSynthetic()) {
                    continue;
                }
                result.add(new FieldInfoImpl(jandexIndex, annotationOverlays, jandexField));
            }
        });
        return Collections.unmodifiableList(result);
    }

    @Override
    public Collection<RecordComponentInfo> recordComponents() {
        return jandexDeclaration.recordComponents()
                .stream()
                .map(it -> new RecordComponentInfoImpl(jandexIndex, annotationOverlays, it))
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    AnnotationsOverlay<org.jboss.jandex.ClassInfo> annotationsOverlay() {
        return annotationOverlays.classes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ClassInfoImpl classInfo = (ClassInfoImpl) o;
        return Objects.equals(name, classInfo.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
