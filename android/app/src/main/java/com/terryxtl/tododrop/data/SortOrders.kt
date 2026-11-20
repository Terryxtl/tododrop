package com.terryxtl.tododrop.data

/**
 * 拖拽排序后给被拖动的这组待办重新分配 sortOrder。
 *
 * 不重写成 0..n，而是把这组待办原有的 sortOrder 排好序，按新顺序依次发回去。
 * 这样已完成待办（不在列表里）占着的序号不会被抢走，恢复成未完成后仍回到原来的相对位置。
 *
 * 原有序号里有重复（旧版本的数据或手写的备份）时，重复的往后顺延，保证结果严格递增，
 * 否则并列的两条会按 id 排，拖拽结果就丢了。
 *
 * @param current 这组待办当前的 id → sortOrder；不在其中的 id（已被删除）会被忽略
 * @param idsInOrder 拖拽后的新顺序
 * @return 需要写回的 id → 新 sortOrder（只包含值有变化的）
 */
internal fun reassignSortOrders(current: Map<Long, Int>, idsInOrder: List<Long>): Map<Long, Int> {
    val ids = idsInOrder.distinct().filter { it in current }
    val slots = ids.map { current.getValue(it) }.sorted().toMutableList()
    for (i in 1 until slots.size) {
        if (slots[i] <= slots[i - 1]) slots[i] = slots[i - 1] + 1
    }
    val result = LinkedHashMap<Long, Int>()
    ids.forEachIndexed { index, id ->
        if (current.getValue(id) != slots[index]) result[id] = slots[index]
    }
    return result
}
