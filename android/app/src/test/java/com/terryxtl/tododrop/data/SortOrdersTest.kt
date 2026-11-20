package com.terryxtl.tododrop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SortOrdersTest {

    /** 把写回结果套到当前值上，得到拖拽后的完整 sortOrder。 */
    private fun apply(current: Map<Long, Int>, idsInOrder: List<Long>): Map<Long, Int> =
        current + reassignSortOrders(current, idsInOrder)

    @Test
    fun reusesExistingSlotsSoDoneTodosKeepTheirPlace() {
        // 未完成：1(0) 2(1) 3(3)；已完成的待办占着 2。把 3 拖到最前。
        val open = mapOf(1L to 0, 2L to 1, 3L to 3)
        val after = apply(open, listOf(3L, 1L, 2L))
        assertEquals(mapOf(3L to 0, 1L to 1, 2L to 3), after)
        // 序号 2 没有被占用，那条已完成的待办恢复后仍然排在 1 和 2 之间
        assertTrue(2 !in after.values)
    }

    @Test
    fun resultIsStrictlyIncreasingInTheNewOrder() {
        val open = mapOf(10L to 5, 11L to 9, 12L to 2, 13L to 7)
        val order = listOf(13L, 10L, 12L, 11L)
        val after = apply(open, order)
        val orders = order.map { after.getValue(it) }
        assertEquals(orders.sorted(), orders)
        assertEquals(orders.size, orders.toSet().size)
        // 用的仍是原来那几个序号
        assertEquals(open.values.sorted(), orders)
    }

    @Test
    fun onlyChangedItemsAreWritten() {
        val open = mapOf(1L to 0, 2L to 4, 3L to 8)
        assertTrue(reassignSortOrders(open, listOf(1L, 2L, 3L)).isEmpty())
        assertEquals(mapOf(2L to 0, 1L to 4), reassignSortOrders(open, listOf(2L, 1L, 3L)))
    }

    @Test
    fun duplicateSlotsAreSpreadOut() {
        // 旧数据里序号可能重复，重复的往后顺延，否则并列项会按 id 排，拖拽结果丢失
        val open = mapOf(1L to 0, 2L to 0, 3L to 0)
        val after = apply(open, listOf(3L, 1L, 2L))
        assertEquals(mapOf(3L to 0, 1L to 1, 2L to 2), after)
    }

    @Test
    fun unknownAndRepeatedIdsAreIgnored() {
        val open = mapOf(1L to 3, 2L to 6)
        val result = reassignSortOrders(open, listOf(2L, 99L, 2L, 1L))
        assertEquals(mapOf(2L to 3, 1L to 6), result)
    }

    @Test
    fun emptyInputGivesNothingToWrite() {
        assertTrue(reassignSortOrders(emptyMap(), emptyList()).isEmpty())
        assertTrue(reassignSortOrders(mapOf(1L to 0), emptyList()).isEmpty())
    }
}
