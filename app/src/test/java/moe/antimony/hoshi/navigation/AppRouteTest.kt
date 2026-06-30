package moe.antimony.hoshi.navigation

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppRouteTest {

    @Test
    fun appRoutesAreSerializableNavigation3Keys() {
        val navKeyClass = Class.forName("androidx.navigation3.runtime.NavKey")
        val routeClass = Class.forName("moe.antimony.hoshi.navigation.AppRoute")
        val routeNames = listOf(
            "MainRoute",
            "BooksRoute",
            "DictionaryRoute",
            "StatisticsRoute",
            "SettingsRoute",
            "SettingsDetailRoute",
            "ReaderRoute",
        )

        assertTrue(navKeyClass.isAssignableFrom(routeClass))
        routeNames.forEach { routeName ->
            val clazz = Class.forName("moe.antimony.hoshi.navigation.AppRoute$$routeName")
            assertTrue("$routeName must implement AppRoute", routeClass.isAssignableFrom(clazz))
            assertTrue("$routeName must implement NavKey", navKeyClass.isAssignableFrom(clazz))
            assertTrue("$routeName must be @Serializable", clazz.isAnnotationPresent(Serializable::class.java))
        }
    }

    @Test
    fun readerRoutesCarryOnlyStableBookIds() {
        assertRouteConstructor("ReaderRoute", String::class.java)
    }

    @Test
    fun legacySasayakiMatchRouteIsRemoved() {
        assertEquals(
            false,
            runCatching {
                Class.forName("moe.antimony.hoshi.navigation.AppRoute\$SasayakiMatchRoute")
            }.isSuccess,
        )
    }

    @Test
    fun settingsDetailRouteUsesTypedSectionKeys() {
        val sectionClass = Class.forName("moe.antimony.hoshi.navigation.SettingsDetailSection")

        assertTrue(sectionClass.isEnum)
        assertTrue(sectionClass.isAnnotationPresent(Serializable::class.java))
        assertEquals(
            listOf(
                "Dictionaries",
                "Anki",
                "Profiles",
                "Appearance",
                "Behavior",
                "Advanced",
                "Diagnostics",
                "About",
            ),
            sectionClass.enumConstants.orEmpty().map { (it as Enum<*>).name },
        )
        assertRouteConstructor("SettingsDetailRoute", sectionClass)
    }

    private fun assertRouteConstructor(routeName: String, vararg parameterTypes: Class<*>) {
        val constructors = Class.forName("moe.antimony.hoshi.navigation.AppRoute$$routeName")
            .declaredConstructors
            .map { constructor -> constructor.parameterTypes.toList() }

        assertTrue(
            "$routeName must expose constructor with ${parameterTypes.toList()}, constructors=$constructors",
            parameterTypes.toList() in constructors,
        )
    }
}
