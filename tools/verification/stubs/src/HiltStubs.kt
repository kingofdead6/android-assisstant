package dagger.hilt

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
annotation class InstallIn(vararg val components: KClass<*>)

@Target(AnnotationTarget.CLASS)
annotation class EntryPoint
