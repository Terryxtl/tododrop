package com.terryxtl.tododrop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTreeTest {

    private fun p(id: Long, parentId: Long? = null) =
        ProjectEntity(id = id, name = "P$id", sortOrder = 0, createdAt = 0L, parentId = parentId)

    private val tree = ProjectTree(listOf(p(1), p(2), p(3, 1), p(4, 3), p(5, 1)))

    @Test
    fun rootsAndChildrenKeepInputOrder() {
        assertEquals(listOf(1L, 2L), tree.roots.map { it.id })
        assertEquals(listOf(3L, 5L), tree.childrenOf(1).map { it.id })
        assertTrue(tree.childrenOf(2).isEmpty())
    }

    @Test
    fun depthsHeightsAndLimits() {
        assertEquals(1, tree.depthOf(1))
        assertEquals(2, tree.depthOf(3))
        assertEquals(3, tree.depthOf(4))
        assertEquals(3, tree.heightOf(1))
        assertEquals(1, tree.heightOf(2))
        assertTrue(tree.canAddChild(3))
        assertFalse(tree.canAddChild(4))
    }

    @Test
    fun pathsSubtreesAndPreorder() {
        assertEquals(listOf(1L, 3L, 4L), tree.pathOf(4).map { it.id })
        assertEquals(listOf(1L, 3L, 4L, 5L), tree.subtreeIds(1))
        assertEquals(listOf(4L), tree.subtreeIds(4))
        assertTrue(tree.subtreeIds(99).isEmpty())
        assertEquals(
            listOf(1L to 1, 3L to 2, 4L to 3, 5L to 2, 2L to 1),
            tree.preorder().map { (proj, depth) -> proj.id to depth },
        )
    }

    @Test
    fun orphansAndCyclesBecomeRoots() {
        val broken = ProjectTree(listOf(p(1, 99), p(2, 3), p(3, 2), p(4, 4), p(5, 2)))
        assertEquals(listOf(1L, 2L, 3L, 4L), broken.roots.map { it.id })
        assertEquals(listOf(5L), broken.childrenOf(2).map { it.id })
        assertEquals(5, broken.preorder().size)
    }
}
