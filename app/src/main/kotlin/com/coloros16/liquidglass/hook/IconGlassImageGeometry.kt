package com.coloros16.liquidglass.hook

import android.graphics.RectF
import android.widget.ImageView

/** Image slot geometry, not a square assumption about the entire engine view. */
internal object IconGlassImageGeometry {
    fun bounds(view:ImageView):RectF? {
        val drawable=view.drawable ?: return null
        // FlexImageView.getDrawable() can return an engine-cache clone whose bounds were
        // never laid out. Its verified appicon View is the actual square image slot.
        if(drawable.bounds.isEmpty) {
            if(!IconGlassAppIconSlot.registered(view))return null
            return RectF(view.paddingLeft.toFloat(),view.paddingTop.toFloat(),
                (view.width-view.paddingRight).toFloat(),(view.height-view.paddingBottom).toFloat())
                .takeIf {valid(view,it)}
        }
        val area=RectF(drawable.bounds)
        view.imageMatrix.mapRect(area)
        area.offset(view.paddingLeft.toFloat(),view.paddingTop.toFloat())
        if(!area.intersect(view.paddingLeft.toFloat(),view.paddingTop.toFloat(),
                (view.width-view.paddingRight).toFloat(),(view.height-view.paddingBottom).toFloat()))return null
        return area.takeIf {valid(view,it)}
    }
    private fun valid(view:ImageView,area:RectF):Boolean {
        val density=view.resources.displayMetrics.density
        return IconGlassPolicy.validSize(area.width(),area.height()) &&
            minOf(area.width(),area.height())>=density*24f && maxOf(area.width(),area.height())<=density*140f
    }
}
