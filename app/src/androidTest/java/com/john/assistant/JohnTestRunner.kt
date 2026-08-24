package com.john.assistant

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Substitutes Hilt's test Application for [JohnApplication] under instrumentation.
 *
 * Without this, instrumented tests boot the real application — which resumes
 * background listening and prunes the user's history on a test device. The
 * runner is named in `testInstrumentationRunner`.
 */
class JohnTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(classLoader, HiltTestApplication::class.java.name, context)
}
