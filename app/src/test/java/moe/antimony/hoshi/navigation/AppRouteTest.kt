package moe.antimony.hoshi.navigation

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppRouteTest {
    @Test
    fun navigation3DependenciesAreDeclaredInVersionCatalogAndAppModule() {
        val versions = File("../gradle/libs.versions.toml").readText()
        val appBuild = File("build.gradle.kts").readText()

        assertTrue(versions.contains("navigation3 = \"1.1.1\""))
        assertTrue(versions.contains("androidx-navigation3-runtime"))
        assertTrue(versions.contains("androidx-navigation3-ui"))
        assertTrue(appBuild.contains("implementation(libs.androidx.navigation3.runtime)"))
        assertTrue(appBuild.contains("implementation(libs.androidx.navigation3.ui)"))
    }

    @Test
    fun appRoutesAreSerializableNavigation3Keys() {
        val navKeyClass = Class.forName("androidx.navigation3.runtime.NavKey")
        val routeClass = Class.forName("moe.antimony.hoshi.navigation.AppRoute")
        val routeNames = listOf(
            "MainRoute",
            "BooksRoute",
            "DictionaryRoute",
            "SettingsRoute",
            "SettingsDetailRoute",
            "ReaderRoute",
            "SasayakiMatchRoute",
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
        assertRouteConstructor("SasayakiMatchRoute", String::class.java)
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
