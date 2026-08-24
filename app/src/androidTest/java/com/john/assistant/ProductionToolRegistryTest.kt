package com.john.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.john.assistant.core.tool.RiskLevel
import com.john.assistant.core.tool.ToolRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Checks the registry John actually ships with.
 *
 * The unit tests assert the tool *contract* against representative tools; this
 * asserts that every real tool honours it, using the graph Hilt assembles. It
 * needs a device because the registry's tools depend on a Context.
 *
 * Run with `./gradlew :app:connectedAndroidTest`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ProductionToolRegistryTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var registry: ToolRegistry

    @Test
    fun everyToolIsAddressableAndDescribed() {
        hiltRule.inject()

        assertTrue("no tools registered", registry.size > 0)

        registry.all().forEach { tool ->
            assertTrue(
                "${tool.name} is not lower_snake_case",
                tool.name.matches(Regex("^[a-z][a-z0-9_]*$")),
            )
            assertTrue("${tool.name} has no description", tool.description.isNotBlank())

            // The description is what the model reads to choose. A terse one
            // produces a model that reaches for the wrong tool.
            assertTrue(
                "${tool.name}'s description is too short to choose from",
                tool.description.length >= 20,
            )
        }
    }

    @Test
    fun toolNamesAreUnique() {
        hiltRule.inject()
        val names = registry.all().map { it.name }
        assertTrue("duplicate tool names", names.size == names.toSet().size)
    }

    @Test
    fun sideEffectingToolsDeclareRiskAndConfirmationWording() {
        hiltRule.inject()

        registry.all()
            .filter { it.riskLevel != RiskLevel.LOW }
            .forEach { tool ->
                val described = tool.describeAction(com.john.assistant.core.tool.ToolArguments.EMPTY)
                // "Do you want me to run send message?" is not a question a
                // person can answer. Every confirming tool must read naturally.
                assertTrue(
                    "${tool.name} has no human confirmation wording",
                    described.isNotBlank() && described != tool.name,
                )
            }
    }

    @Test
    fun theSchemaGivenToTheModelIsValidJson() {
        hiltRule.inject()

        val schema = registry.toJsonSchema()
        assertTrue("empty schema", schema.size > 0)
        registry.definitions().forEach { definition ->
            val json = definition.toJsonSchema().toString()
            assertTrue(json.contains("\"name\":\"${definition.name}\""))
            assertTrue(json.contains("\"parameters\""))
        }
    }
}
