package com.john.assistant.ai

import com.john.assistant.ai.model.ModelCatalogue
import com.john.assistant.ai.model.ModelKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDescriptorTest {

    @Test
    fun `a model needs headroom beyond its own footprint`() {
        val model = ModelCatalogue.LANGUAGE_MODELS.first { it.requiredRamMb == 1_600 }

        // Loading a 1.6 GB model into a 2 GB phone thrashes rather than runs.
        assertFalse(model.fitsIn(deviceRamMb = 2_048))
        assertTrue(model.fitsIn(deviceRamMb = 6_144))
    }

    @Test
    fun `every catalogue entry states its cost and licence`() {
        ModelCatalogue.all().forEach { model ->
            assertTrue("${model.id} has no size", model.sizeMb > 0)
            assertTrue("${model.id} has no RAM figure", model.requiredRamMb > 0)
            assertTrue("${model.id} has no licence", model.licence.isNotBlank())
            assertTrue("${model.id} has no file name", model.fileName.isNotBlank())
        }
    }

    @Test
    fun `no catalogue entry ships a download URL`() {
        // Deliberate: model repositories move, and a stale link that 404s
        // mid-download is worse than asking the user for the address once.
        ModelCatalogue.all().forEach { model ->
            assertTrue("${model.id} hardcodes a URL", model.downloadUrl.isBlank())
        }
    }

    @Test
    fun `ids are unique and resolvable`() {
        val ids = ModelCatalogue.all().map { it.id }
        assertTrue("duplicate model ids", ids.size == ids.toSet().size)
        ids.forEach { assertNotNull(ModelCatalogue.byId(it)) }
    }

    @Test
    fun `models are grouped by kind`() {
        assertTrue(ModelCatalogue.forKind(ModelKind.LANGUAGE).isNotEmpty())
        assertTrue(ModelCatalogue.forKind(ModelKind.SPEECH_TO_TEXT).isNotEmpty())
        assertTrue(
            ModelCatalogue.forKind(ModelKind.LANGUAGE).all { it.kind == ModelKind.LANGUAGE },
        )
    }
}
