package com.nicotv.iptv.ui.common

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/** ImageView that always measures height = width × 3/2 (portrait 2:3 poster ratio). */
class PosterImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = width * 3 / 2
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }
}
