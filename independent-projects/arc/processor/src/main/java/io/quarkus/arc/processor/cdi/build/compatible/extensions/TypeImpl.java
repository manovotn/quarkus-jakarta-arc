package io.quarkus.arc.processor.cdi.build.compatible.extensions;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.types.Type;

import org.jboss.jandex.DotName;

// TODO all subclasses must have equals, hashCode and perhaps also their own toString (though the current one is fine)
abstract class TypeImpl<JandexType extends org.jboss.jandex.Type> implements Type {
    final org.jboss.jandex.IndexView jandexIndex;
    final AllAnnotationOverlays annotationOverlays;
    final JandexType jandexType;

    TypeImpl(org.jboss.jandex.IndexView jandexIndex, AllAnnotationOverlays annotationOverlays, JandexType jandexType) {
        this.jandexIndex = jandexIndex;
        this.annotationOverlays = annotationOverlays;
        this.jandexType = jandexType;
    }

    static Type fromJandexType(org.jboss.jandex.IndexView jandexIndex, AllAnnotationOverlays annotationOverlays,
            org.jboss.jandex.Type jandexType) {
        switch (jandexType.kind()) {
            case VOID:
                return new VoidTypeImpl(jandexIndex, annotationOverlays, jandexType.asVoidType());
            case PRIMITIVE:
                return new PrimitiveTypeImpl(jandexIndex, annotationOverlays, jandexType.asPrimitiveType());
            case CLASS:
                return new ClassTypeImpl(jandexIndex, annotationOverlays, jandexType.asClassType());
            case ARRAY:
                return new ArrayTypeImpl(jandexIndex, annotationOverlays, jandexType.asArrayType());
            case PARAMETERIZED_TYPE:
                return new ParameterizedTypeImpl(jandexIndex, annotationOverlays, jandexType.asParameterizedType());
            case TYPE_VARIABLE:
                return new TypeVariableImpl(jandexIndex, annotationOverlays, jandexType.asTypeVariable());
            case UNRESOLVED_TYPE_VARIABLE:
                return new UnresolvedTypeVariableImpl(jandexIndex, annotationOverlays, jandexType.asUnresolvedTypeVariable());
            case WILDCARD_TYPE:
                return new WildcardTypeImpl(jandexIndex, annotationOverlays, jandexType.asWildcardType());
            default:
                throw new IllegalArgumentException("Unknown type " + jandexType);
        }
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        return jandexType.hasAnnotation(DotName.createSimple(annotationType.getName()));
    }

    @Override
    public boolean hasAnnotation(Predicate<AnnotationInfo> predicate) {
        return jandexType.annotations()
                .stream()
                .anyMatch(it -> predicate.test(new AnnotationInfoImpl(jandexIndex, annotationOverlays, it)));
    }

    @Override
    public <T extends Annotation> AnnotationInfo annotation(Class<T> annotationType) {
        return new AnnotationInfoImpl(jandexIndex, annotationOverlays,
                jandexType.annotation(DotName.createSimple(annotationType.getName())));
    }

    @Override
    public <T extends Annotation> Collection<AnnotationInfo> repeatableAnnotation(Class<T> annotationType) {
        return annotationsWithRepeatable(jandexType, DotName.createSimple(annotationType.getName()), jandexIndex)
                .stream()
                .map(it -> new AnnotationInfoImpl(jandexIndex, annotationOverlays, it))
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public Collection<AnnotationInfo> annotations(Predicate<AnnotationInfo> predicate) {
        return jandexType.annotations()
                .stream()
                .map(it -> new AnnotationInfoImpl(jandexIndex, annotationOverlays, it))
                .filter(predicate)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public Collection<AnnotationInfo> annotations() {
        return annotations(it -> true);
    }

    @Override
    public String toString() {
        return jandexType.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof TypeImpl))
            return false;
        TypeImpl<?> type = (TypeImpl<?>) o;
        return Objects.equals(jandexType, type.jandexType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jandexType);
    }

    // ---
    // Jandex doesn't have `annotationsWithRepeatable` method for types

    private static final DotName REPEATABLE = DotName.createSimple("java.lang.annotation.Repeatable");

    private static Collection<org.jboss.jandex.AnnotationInstance> annotationsWithRepeatable(org.jboss.jandex.Type type,
            DotName name, org.jboss.jandex.IndexView index) {

        org.jboss.jandex.AnnotationInstance ret = type.annotation(name);
        if (ret != null) {
            // Annotation present - no need to try to find repeatable annotations
            return Collections.singletonList(ret);
        }
        org.jboss.jandex.ClassInfo annotationClass = index.getClassByName(name);
        if (annotationClass == null) {
            throw new IllegalArgumentException("Index does not contain the annotation definition: " + name);
        }
        if (!annotationClass.isAnnotation()) {
            throw new IllegalArgumentException("Not an annotation type: " + annotationClass);
        }
        org.jboss.jandex.AnnotationInstance repeatable = annotationClass.declaredAnnotation(REPEATABLE);
        if (repeatable == null) {
            return Collections.emptyList();
        }
        org.jboss.jandex.Type containingType = repeatable.value().asClass();
        org.jboss.jandex.AnnotationInstance containing = type.annotation(containingType.name());
        if (containing == null) {
            return Collections.emptyList();
        }
        org.jboss.jandex.AnnotationInstance[] values = containing.value().asNestedArray();
        return Arrays.asList(values);
    }
}
