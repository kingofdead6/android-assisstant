package androidx.annotation

@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
)
annotation class RequiresApi(val value: Int)

@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FIELD,
)
annotation class RequiresPermission(
    val value: String = "",
    val allOf: Array<String> = [],
    val anyOf: Array<String> = [],
)
