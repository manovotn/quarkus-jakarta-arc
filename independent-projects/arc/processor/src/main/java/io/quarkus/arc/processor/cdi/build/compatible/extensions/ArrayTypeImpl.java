package io.quarkus.arc.processor.cdi.build.compatible.extensions;

import jakarta.enterprise.lang.model.types.ArrayType;
import jakarta.enterprise.lang.model.types.Type;

class ArrayTypeImpl extends TypeImpl<org.jboss.jandex.ArrayType> implements ArrayType {
    ArrayTypeImpl(org.jboss.jandex.IndexView jandexIndex, AllAnnotationOverlays annotationOverlays,
            org.jboss.jandex.ArrayType jandexType) {
        super(jandexIndex, annotationOverlays, jandexType);
    }

    @Override
    public Type componentType() {
        int dimensions = jandexType.dimensions();
        if (dimensions < 1) {
            throw new IllegalStateException("Invalid array type: " + jandexType);
        }

        org.jboss.jandex.Type componentType = dimensions == 1
                ? jandexType.component()
                : org.jboss.jandex.ArrayType.create(jandexType.component(), dimensions - 1);
        return fromJandexType(jandexIndex, annotationOverlays, componentType);
    }
}
