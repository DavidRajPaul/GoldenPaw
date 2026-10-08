package com.goldenpaw

import com.goldenpaw.domain.model.ActivityKind
import com.goldenpaw.domain.model.Badge
import com.goldenpaw.domain.model.DocumentType
import com.goldenpaw.domain.model.SymptomType
import com.goldenpaw.ui.designsystem.GpIcons
import com.goldenpaw.ui.designsystem.icon
import com.goldenpaw.ui.navigation.NavAction
import com.goldenpaw.ui.navigation.Navigator
import com.goldenpaw.ui.navigation.Route
import com.goldenpaw.ui.navigation.TopLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationTest {
    @Test
    fun tabSwitchesKnowTheirDirection() {
        val nav = Navigator(Route.Today)
        nav.switchTab(Route.Insights)
        assertEquals(NavAction.TAB, nav.lastAction)
        assertEquals(1, nav.tabDirection)
        nav.switchTab(Route.Journal)
        assertEquals(-1, nav.tabDirection)
        nav.switchTab(Route.Settings)
        assertEquals(1, nav.tabDirection)
        nav.switchTab(Route.Today)
        assertEquals(-1, nav.tabDirection)
        assertEquals(listOf<Route>(Route.Today), nav.backStack.map { it.route })
    }

    @Test
    fun todayStaysTheRootUnderOtherTabs() {
        val nav = Navigator(Route.Today)
        nav.switchTab(Route.Pets)
        nav.navigate(Route.PetDetail("pet"))
        nav.navigate(Route.RecordScan("pet"))
        assertFalse(nav.isTopLevel)
        nav.navigate(Route.RecordScan("pet")) // same route twice is ignored
        assertEquals(4, nav.backStack.size)
        nav.switchTab(Route.Journal)
        assertEquals(listOf<Route>(Route.Today, Route.Journal), nav.backStack.map { it.route })
        assertTrue(nav.isTopLevel)
    }

    @Test
    fun recordRoutesBackOut() {
        val nav = Navigator(Route.Today)
        nav.navigate(Route.RecordScan("pet"))
        nav.back()
        nav.navigate(Route.RecordDetail("pet", "r1"))
        assertEquals(Route.RecordDetail("pet", "r1"), nav.current.route)
        assertTrue(nav.back())
        assertFalse(nav.back())
    }

    @Test
    fun topLevelLookup() {
        assertEquals(TopLevel.INSIGHTS, TopLevel.of(Route.Insights))
        assertNull(TopLevel.of(Route.Reminders))
        TopLevel.entries.forEach { assertTrue(it.icon != it.selectedIcon, "${it.name} needs distinct outlined / filled icons") }
    }

    @Test
    fun everyConceptHasAnIcon() {
        DocumentType.entries.forEach { assertNotNull(it.icon) }
        SymptomType.entries.forEach { assertNotNull(it.icon) }
        Badge.entries.forEach { assertNotNull(it.icon) }
        ActivityKind.entries.forEach { assertNotNull(it.icon) }
        assertEquals(5, GpIcons.faces.size)
    }
}
