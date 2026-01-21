package com.tencent.twetalk_sdk_demo.utils

import android.app.Activity
import android.content.res.Configuration
import android.view.View

/**
 * 小屏幕适配辅助类
 * 
 * 屏幕分级（基于 smallestScreenWidthDp）：
 * - 极小屏（≤320dp）：手表级别，启用简化模式
 * - 小屏（320-400dp）：小型手机/大屏手表
 * - 普通屏（>400dp）：标准智能手机
 */
object ScreenAdaptHelper {
    
    /** 极小屏阈值（dp） */
    const val THRESHOLD_TINY_SCREEN = 320
    
    /** 小屏阈值（dp） */
    const val THRESHOLD_SMALL_SCREEN = 400
    
    /**
     * 判断是否为极小屏幕（≤320dp）
     * 极小屏需要启用简化模式
     */
    fun isTinyScreen(activity: Activity): Boolean {
        return activity.resources.configuration.smallestScreenWidthDp <= THRESHOLD_TINY_SCREEN
    }
    
    /**
     * 判断是否为小屏幕（≤400dp）
     */
    fun isSmallScreen(activity: Activity): Boolean {
        return activity.resources.configuration.smallestScreenWidthDp <= THRESHOLD_SMALL_SCREEN
    }
    
    /**
     * 获取最小屏幕宽度（dp）
     */
    fun getSmallestScreenWidthDp(activity: Activity): Int {
        return activity.resources.configuration.smallestScreenWidthDp
    }
    
    /**
     * 批量设置 View 可见性
     */
    fun setViewsVisibility(visibility: Int, vararg views: View?) {
        views.forEach { view ->
            view?.visibility = visibility
        }
    }
    
    /**
     * 隐藏指定的 Views（用于简化模式）
     */
    fun hideViews(vararg views: View?) {
        setViewsVisibility(View.GONE, *views)
    }
    
    /**
     * 显示指定的 Views
     */
    fun showViews(vararg views: View?) {
        setViewsVisibility(View.VISIBLE, *views)
    }
}
