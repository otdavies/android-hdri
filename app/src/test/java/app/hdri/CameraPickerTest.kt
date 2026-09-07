package app.hdri

import app.hdri.capture.*
import app.hdri.core.Lens
import org.junit.Assert.*
import org.junit.Test

class CameraPickerTest {
    private fun camera(key: String, ratio: Double, zoom: Float? = null, physical: String? = null) =
        CameraChoice(
            key,
            "0",
            physical,
            "Uncurated route",
            Lens(160, 120, 100.0 * ratio, 100.0 * ratio, 80.0, 60.0),
            90,
            zoom,
            1,
            ratio,
        )

    @Test
    fun pixelStyleCatalogOffersOneLogicalUltrawideWithoutDuplicateMainOrPhysicalRoutes() {
        val entries =
            listOf(
                camera("camera:0:main", 1.0, physical = "main"),
                camera("camera:0:wide", .5, physical = "wide"),
                camera("standalone", .5),
                camera("zoom:0:0.5", .5, .5f),
                camera("alias", .6, .6f),
                camera("tele", 3.0),
            )
        val scan = CameraScan(entries, emptyList())
        assertEquals(listOf("zoom:0:0.5"), scan.captureChoices.map { it.key })
        assertEquals("Ultrawide · 0.5×", scan.captureChoices.single().label)
        // Older sessions can still resolve their exact route. Picker cleanup is not migration.
        assertTrue(scan.choices.any { it.key == "camera:0:wide" })
    }

    @Test
    fun independentlyOpenableWideIsOfferedWhenLogicalZoomIsAbsent() {
        val scan =
            CameraScan(
                listOf(
                    camera("main", 1.0),
                    camera("wide", .65),
                    camera("physical-only", .5, physical = "2"),
                ),
                emptyList(),
            )
        assertEquals(listOf("wide"), scan.captureChoices.map { it.key })
        assertTrue(CameraScan(listOf(camera("main", 1.0)), emptyList()).captureChoices.isEmpty())
    }
}
