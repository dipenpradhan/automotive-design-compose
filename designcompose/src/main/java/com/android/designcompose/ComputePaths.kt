/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.designcompose

import android.graphics.PointF
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.core.graphics.minus
import androidx.core.graphics.plus
import com.android.designcompose.proto.StrokeAlignType
import com.android.designcompose.proto.bottom
import com.android.designcompose.proto.left
import com.android.designcompose.proto.right
import com.android.designcompose.proto.strokeAlignFromInt
import com.android.designcompose.proto.strokeCapFromInt
import com.android.designcompose.proto.toUniform
import com.android.designcompose.proto.top
import com.android.designcompose.serdegen.BoxShadow
import com.android.designcompose.serdegen.ShadowBox
import com.android.designcompose.serdegen.Shape
import com.android.designcompose.serdegen.StrokeCap
import com.android.designcompose.serdegen.VectorArc
import com.android.designcompose.serdegen.ViewShape
import com.android.designcompose.serdegen.ViewStyle
import kotlin.jvm.optionals.getOrNull

/// ComputedPaths is a set of paths derived from a shape and style definition. These
/// paths are based on the style information (known at document conversion time)
/// and layout information (only known at runtime).
///
/// In the general path case, the various fills, strokes, clips, and shadow bounds
/// are computed using "Path Operations" (boolean ops on paths, and offset paths
/// computed using Skia's "getFillPath" function).
///
/// In some special cases, like frames and rectangles, a simpler algorithm is used
/// because the geometry is trivial to calculate. This results in simpler paths to
/// render, and may be less compute intensive.
internal class ComputedPaths(
    /// The paths that need to be filled with the Background brush for
    /// this shape.
    val fills: List<Path>,

    /// The paths that need to be filled with the Stroke brush for this
    /// shape.
    var strokes: List<Path>,

    /// The paths that need to be clipped out (for outset shadows that
    /// don't paint under the background) or clipped in for inset shadows.
    ///
    /// These paths are different from the "fills" because the shadow clip
    /// is based on the stroke bounds, and not the background bounds.
    val shadowClips: List<Path>,

    /// The paths that are filled for shadows. The shadow style field is
    /// referenced, which includes details like the inset/outset of the
    /// shadow, its offset, and its color.
    val shadowFills: List<ComputedShadow>,

    // Optional stroke end cap
    var strokeCap: androidx.compose.ui.graphics.StrokeCap?,
)

internal class ComputedShadow(val fills: List<Path>, val shadowStyle: BoxShadow)

/// Maintain a cache of computed paths, as some paths and strokes are quite expensive to
/// compute (if we need to provide a stroke for a path), causing `computePaths` to show
/// up prominently in profiles for Squoosh without a cache.
internal class ComputedPathCache {
    internal class Entry(
        val computedPaths: ComputedPaths,
        // We could be more selective than using the entire style, and styles are
        // immutable so we could also simply do object identity comparison rather
        // than a deep equality comparison.
        val viewShape: ViewShape,
        val style: ViewStyle,
        val density: Float,
        val frameSize: Size,
        val overrideSize: Size?,
        val customArcAngle: Boolean,
        val cornerRadius: FloatArray,
    )

    private var cache: HashMap<Int, Entry> = HashMap()
    private var nextGeneration: HashMap<Int, Entry> = HashMap()

    fun get(layoutId: Int): Entry? {
        return cache[layoutId]
    }

    fun put(layoutId: Int, data: Entry) {
        // We accumulate new entries in the `nextGeneration` field, with the caller responsible
        // for moving existing cache entries from `cache` to `nextGeneration` (by calling get and
        // put).
        //
        // The owner of the `ComputedPathCache` (e.g.: `SquooshRoot`) ensures that `collect` is
        // called between renders, which moves `nextGeneration` to `cache`, garbage collecting all
        // of the entries that weren't used in the most recent render.
        nextGeneration[layoutId] = data
    }

    /// Perform a garbage collection by throwing out all of the cache entries that were not used
    /// since the last time `collect` was called. This avoids the cache getting large.
    fun collect() {
        cache.clear()
        cache.putAll(nextGeneration)
        nextGeneration.clear()
    }
}

private fun ViewShape.extractCornerRadii(variableState: VariableState): FloatArray {
    val cornerRadius =
        when (val shape = this.shape.getOrNull()) {
            is Shape.RoundRect -> shape.value.corner_radii
            is Shape.VectorRect -> shape.value.corner_radii
            else -> return floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
        }
    return cornerRadius.map { radius -> radius.getValue(variableState) }.toFloatArray()
}

internal fun ViewShape.computePaths(
    style: ViewStyle,
    density: Float,
    frameSize: Size,
    overrideSize: Size?,
    customArcAngle: Boolean,
    layoutId: Int,
    variableState: VariableState,
    pathCache: ComputedPathCache,
): ComputedPaths {
    // Before we compute anything, see if this path didn't change since the last
    // time.
    val cornerRadius = this.extractCornerRadii(variableState)
    val cacheEntry = pathCache.get(layoutId)
    if (
        cacheEntry != null &&
            cacheEntry.viewShape == this &&
            cacheEntry.style == style &&
            cacheEntry.density == density &&
            cacheEntry.frameSize == frameSize &&
            cacheEntry.overrideSize == overrideSize &&
            cacheEntry.customArcAngle == customArcAngle &&
            cacheEntry.cornerRadius.contentEquals(cornerRadius)
    ) {
        pathCache.put(layoutId, cacheEntry)
        return cacheEntry.computedPaths
    }

    fun getPaths(
        path: List<com.android.designcompose.serdegen.Path>,
        stroke: List<com.android.designcompose.serdegen.Path>,
    ): Pair<List<Path>, List<Path>> {
        // TODO GH-673 support vector paths with scale constraints. Use vectorScaleX, vectorScaleY
        val scaleX = 1F
        val scaleY = 1F
        return Pair(
            path.map { p -> p.asPath(density, scaleX, scaleY) },
            stroke.map { p -> p.asPath(density, scaleX, scaleY) },
        )
    }
    fun getRectSize(overrideSize: Size?, style: ViewStyle, density: Float): Size {
        return getNodeRenderSize(overrideSize, frameSize, style, layoutId, density)
    }
    var strokeCap: androidx.compose.ui.graphics.StrokeCap? = null
    // Fill then stroke.
    val (fills: List<Path>, precomputedStrokes: List<Path>) =
        when (val shape = this.shape.getOrNull()) {
            is Shape.Rect -> {
                return computeRoundRectPathsFast(
                    style,
                    cornerRadius,
                    density,
                    getRectSize(overrideSize, style, density),
                )
            }
            is Shape.RoundRect -> {
                return computeRoundRectPathsFast(
                    style,
                    cornerRadius,
                    density,
                    getRectSize(overrideSize, style, density),
                )
            }
            is Shape.VectorRect -> {
                return computeRoundRectPathsFast(
                    style,
                    cornerRadius,
                    density,
                    getRectSize(overrideSize, style, density),
                )
            }
            is Shape.Path -> {
                strokeCap = strokeCapFromInt(shape.value.stroke_cap).toComposeStrokeCap()
                getPaths(shape.value.paths, shape.value.strokes)
            }
            is Shape.Arc -> {
                if (
                    !customArcAngle &&
                        (shape.value.paths.isNotEmpty() || shape.value.strokes.isNotEmpty())
                ) {
                    // Render normally with Figma provided fill/stroke path
                    getPaths(shape.value.paths, shape.value.strokes)
                } else {
                    // We have a custom angle set by a meter customization, so we can't use
                    // the path provided by Figma. Instead, we construct our own path given
                    // the arc parameters
                    if (shape.value.inner_radius < 1.0F) {
                        computeArcPath(frameSize, shape.value)
                    } else {
                        computeArcStrokePath(frameSize, shape.value, style, density)
                    }
                }
            }
            else -> {
                val path = Path()
                val size = getRectSize(overrideSize, style, density)
                path.addRect(Rect(0.0f, 0.0f, size.width, size.height))
                Pair(listOf(path), listOf())
            }
        }

    // We need to make the stroke path if there is any stroke.
    // * Center stroke -> just use the stroke width.
    // * Outer stroke -> double the stroke width, clip out the inner bit
    // * Inner stroke -> double the stroke width, clip out the outer bit.
    val strokeAlignType = strokeAlignFromInt(style.node_style.stroke.stroke_align)
    val rawStrokeWidth =
        when (strokeAlignType) {
            StrokeAlignType.Center -> style.node_style.stroke.stroke_weight.toUniform() * density
            StrokeAlignType.Inside ->
                style.node_style.stroke.stroke_weight.toUniform() * 2.0f * density
            StrokeAlignType.Outside ->
                style.node_style.stroke.stroke_weight.toUniform() * 2.0f * density
            else -> style.node_style.stroke.stroke_weight.toUniform() * density
        }
    val shadowStrokeWidth =
        when (strokeAlignType) {
            StrokeAlignType.Center -> style.node_style.stroke.stroke_weight.toUniform() * density
            StrokeAlignType.Outside ->
                style.node_style.stroke.stroke_weight.toUniform() * 2.0f * density
            else -> 0.0f
        }
    // Build a list of stroke paths, and also build a set of filled paths for shadow
    // painting.
    val strokePaint = android.graphics.Paint()
    strokePaint.style = android.graphics.Paint.Style.STROKE
    strokePaint.strokeWidth = rawStrokeWidth

    val strokes =
        if (fills.isEmpty()) {
            // Sometimes an object has no fill at all (it has no area, because it is
            // just a stroke or line), in which case we use the strokes from the shape.
            precomputedStrokes
        } else {
            // Normally we generate the stroke from the fill path. This lets us have
            // runtime determined width/height for things we're stroking.
            fills.map { fill ->
                val strokePath = android.graphics.Path()
                strokePaint.getFillPath(fill.asAndroidPath(), strokePath)
                when (strokeAlignType) {
                    StrokeAlignType.Outside ->
                        strokePath.op(fill.asAndroidPath(), android.graphics.Path.Op.DIFFERENCE)
                    StrokeAlignType.Inside ->
                        strokePath.op(fill.asAndroidPath(), android.graphics.Path.Op.INTERSECT)
                    else -> {}
                }
                strokePath.asComposePath()
            }
        }

    // Shadow clip calculation
    val shadowBoundsPaint = android.graphics.Paint()
    shadowBoundsPaint.style = android.graphics.Paint.Style.FILL_AND_STROKE
    shadowBoundsPaint.strokeWidth = shadowStrokeWidth
    val shadowPaths =
        fills.map { fill ->
            val shadowPath = android.graphics.Path()
            shadowBoundsPaint.getFillPath(fill.asAndroidPath(), shadowPath)
            shadowPath.asComposePath()
        }

    val shadowOutlinePaint = android.graphics.Paint()
    shadowOutlinePaint.style = android.graphics.Paint.Style.FILL_AND_STROKE
    val shadowSpreadPaint = android.graphics.Paint()
    shadowSpreadPaint.style = android.graphics.Paint.Style.STROKE

    // Shadow path calculation
    val computedShadows: List<ComputedShadow> =
        style.node_style.box_shadow.mapNotNull { shadow ->
            when (val shadowBox = shadow.shadow_box.get()) {
                is ShadowBox.Outset -> {
                    // To calculate the outset path, we must inflate our outer bounds (our fill
                    // path plus the stroke width) plus the shadow spread. Since Skia always
                    // centers strokes, we do this by adding double the spread to the shadow
                    // stroke width.
                    shadowBoundsPaint.strokeWidth =
                        shadowStrokeWidth + shadowBox.value.spread_radius * 2.0f * density
                    val shadowOutlines =
                        fills.map { fill ->
                            val shadowPath = android.graphics.Path()
                            shadowBoundsPaint.getFillPath(fill.asAndroidPath(), shadowPath)
                            shadowPath.asComposePath()
                        }
                    ComputedShadow(shadowOutlines, shadow)
                }
                is ShadowBox.Inset -> {
                    // Inset shadows are applied to the "stroke bounds", not the fill bounds. So we
                    // must inflate our fill bounds out to the stroke bounds by applying a stroke
                    // and
                    // taking the fill path.
                    //
                    // We then invert the fill path so that we're filling the area that's not the
                    // stroke
                    // bounds. Then we offset it and blur it to make the inset shadow.
                    //
                    // If we have a spread that's larger than what we use to expand to make the fill
                    // then we stroke the excess spread and subtract it from the fill to make the
                    // path.

                    val spreadWidth = shadowBox.value.spread_radius * 2.0f * density
                    val needSpreadStroke = spreadWidth > shadowStrokeWidth
                    if (!needSpreadStroke)
                        shadowOutlinePaint.strokeWidth = shadowStrokeWidth - spreadWidth
                    else shadowSpreadPaint.strokeWidth = spreadWidth - shadowStrokeWidth

                    val shadowOutlines =
                        fills.map { fill ->
                            val shadowPath = android.graphics.Path()
                            if (!needSpreadStroke) {
                                shadowOutlinePaint.getFillPath(fill.asAndroidPath(), shadowPath)
                            } else {
                                val spreadStroke = android.graphics.Path()
                                shadowSpreadPaint.getFillPath(fill.asAndroidPath(), spreadStroke)
                                shadowPath.op(
                                    fill.asAndroidPath(),
                                    spreadStroke,
                                    android.graphics.Path.Op.DIFFERENCE,
                                )
                            }

                            shadowPath.toggleInverseFillType()

                            shadowPath.asComposePath()
                        }
                    ComputedShadow(shadowOutlines, shadow)
                }
                else -> null
            }
        }

    val computedPaths = ComputedPaths(fills, strokes, shadowPaths, computedShadows, strokeCap)
    pathCache.put(
        layoutId,
        ComputedPathCache.Entry(
            computedPaths,
            this,
            style,
            density,
            frameSize,
            overrideSize,
            customArcAngle,
            cornerRadius,
        ),
    )

    return computedPaths
}

// GEOMETRY UTILITIES

// Draw a stroke path for the given arc by simply drawing a single arc, then giving it a stroke
// width and converting it to a fill path using android.graphics.Paint.getFillPath()
private fun computeArcStrokePath(
    frameSize: Size,
    shape: VectorArc,
    style: ViewStyle,
    density: Float,
): Pair<List<Path>, List<Path>> {
    val path = Path()
    var left = 0.0f
    var top = 0.0f
    var width = frameSize.width
    var height = frameSize.height
    val strokeWidth = style.node_style.stroke.stroke_weight.toUniform() * density
    val halfStrokeWidth = strokeWidth * 0.5f

    when (strokeAlignFromInt(style.node_style.stroke.stroke_align)) {
        StrokeAlignType.Inside -> {
            left += halfStrokeWidth
            top += halfStrokeWidth
            width -= halfStrokeWidth
            height -= halfStrokeWidth
        }
        StrokeAlignType.Outside -> {
            left -= halfStrokeWidth
            top -= halfStrokeWidth
            width += halfStrokeWidth
            height += halfStrokeWidth
        }
        else -> {}
    }
    path.addArc(
        Rect(left, top, width, height),
        // Arc angles rotate in the opposite direction than node rotations. To keep them consistent,
        // negate the angles
        -shape.start_angle_degrees,
        -shape.sweep_angle_degrees,
    )

    val arcPaint = android.graphics.Paint()
    arcPaint.style = android.graphics.Paint.Style.STROKE
    arcPaint.strokeWidth = strokeWidth
    arcPaint.strokeCap =
        when (strokeCapFromInt(shape.stroke_cap)) {
            is StrokeCap.Round -> android.graphics.Paint.Cap.ROUND
            is StrokeCap.Square -> android.graphics.Paint.Cap.SQUARE
            else -> android.graphics.Paint.Cap.BUTT
        }
    val arcStrokePath = android.graphics.Path()
    arcPaint.getFillPath(path.asAndroidPath(), arcStrokePath)
    return Pair(listOf(), listOf(arcStrokePath.asComposePath()))
}

// Draw a path around the shape of the given arc and return the path. This does this with the
// following steps:
// Draw outer arc
// Draw cubic bezier to segment joining outer arc to inner
// Draw segment joining outer arc to inner
// Draw cubic bezier from segment to inner arc
// Draw inner arc
// Draw cubic bezier to segment joining inner arc to outer
// Draw segment joining inner arc to outer
// Draw cubic bezier from segment to outer arc (starting point)
// Close path
private fun computeArcPath(frameSize: Size, shape: VectorArc): Pair<List<Path>, List<Path>> {
    // Arc angles rotate in the opposite direction than node rotations. To keep them consistent,
    // negate the angles
    val startAngleDegrees = -shape.start_angle_degrees
    val sweepAngleDegrees = -shape.sweep_angle_degrees
    val fWidth = frameSize.width
    val fHeight = frameSize.height
    val positiveSweep = sweepAngleDegrees >= 0
    val sweepAngle = if (positiveSweep) sweepAngleDegrees else -sweepAngleDegrees
    val startAngle = if (positiveSweep) startAngleDegrees else startAngleDegrees - sweepAngle
    val endAngle = if (positiveSweep) startAngle + sweepAngle else startAngleDegrees
    val cornerRadius = shape.corner_radius
    val angleDirection = if (endAngle > startAngle) 1.0F else -1.0F

    val outerRadius = PointF(fWidth / 2F, fHeight / 2F)
    val outerCircumference = outerRadius.ellipseCircumferenceFromRadius()
    val outerArcLength = sweepAngle / 360F * outerCircumference
    val shapeInnerRadius = shape.inner_radius.coerceAtLeast(0.001F)
    val innerSize = Size(fWidth * shapeInnerRadius, fHeight * shapeInnerRadius)
    val innerRadius = PointF(innerSize.width / 2.0F, innerSize.height / 2.0F)
    val innerCircumference = innerRadius.ellipseCircumferenceFromRadius()
    val innerArcLength = sweepAngle / 360F * innerCircumference
    val strokeWidth = (outerRadius.x - innerRadius.x).coerceAtMost(outerRadius.y - innerRadius.y)
    val outerCircumferenceOffset =
        (Math.PI.toFloat() * cornerRadius / 2F)
            .coerceAtMost(strokeWidth / 2F)
            .coerceAtMost(outerArcLength / 2F)
    val innerCircumferenceOffset =
        (Math.PI.toFloat() * cornerRadius / 2F)
            .coerceAtMost(strokeWidth / 2F)
            .coerceAtMost(innerArcLength / 2F)
    val outerTangentOffset = outerCircumferenceOffset * 0.6F
    val innerTangentOffset = innerCircumferenceOffset * 0.6F
    val outerAngleOffset = outerCircumferenceOffset / outerCircumference * 360F
    val innerAngleOffset = (innerCircumferenceOffset / innerCircumference * 360F)
    val outerStartAngle = startAngle + outerAngleOffset * angleDirection
    val outerEndAngle = endAngle - outerAngleOffset * angleDirection
    val innerStartAngle = startAngle + innerAngleOffset * angleDirection
    val innerEndAngle = endAngle - innerAngleOffset * angleDirection

    val path = Path()

    // 1. Draw outer arc from start to end
    val outerRect = Rect(0.0F, 0.0F, fWidth, fHeight)
    path.arcTo(outerRect, outerStartAngle, outerEndAngle - outerStartAngle, false)

    // 2. Draw end outer bezier curve for rounded corners
    // End outer rounded corner bezier start point
    var cubicPoint1 = outerEndAngle.pointAtAngle(frameSize, outerRadius)

    // End outer rounded corner bezier first control point
    val endOuterArcTangent = (outerEndAngle + 90F).unitVectorFromAngle()
    var control1 = cubicPoint1 + endOuterArcTangent * outerTangentOffset

    // End outer rounded corner end point
    val endAngleVector = endAngle.unitVectorFromAngle()
    val outerCornerPoint = endAngle.pointAtAngle(frameSize, outerRadius)
    var cubicPoint2 = outerCornerPoint - endAngleVector * outerCircumferenceOffset

    // End outer rounded corner bezier second control point
    var control2 = cubicPoint2 + endAngleVector * outerTangentOffset
    path.cubicTo(control1.x, control1.y, control2.x, control2.y, cubicPoint2.x, cubicPoint2.y)

    // 3. Draw connecting line and end inner bezier curve for rounded corners
    // End inner rounded corner bezier start point
    val endInnerCornerPoint = endAngle.pointAtAngle(frameSize, innerRadius)
    cubicPoint1 = endInnerCornerPoint + endAngleVector * outerCircumferenceOffset

    // Draw line connecting end outer to end inner rounded corner
    path.lineTo(cubicPoint1.x, cubicPoint1.y)

    // End inner rounded corner bezier first control point
    control1 = cubicPoint1 - endAngleVector * innerTangentOffset

    // End inner rounded corner end point
    cubicPoint2 = innerEndAngle.pointAtAngle(frameSize, innerRadius)

    // End inner rounded corner bezier second control point
    val endInnerArcTangent = (innerEndAngle + 90F).unitVectorFromAngle()
    control2 = cubicPoint2 + endInnerArcTangent * innerTangentOffset
    path.cubicTo(control1.x, control1.y, control2.x, control2.y, cubicPoint2.x, cubicPoint2.y)

    // 4. Draw inner arc from end to start
    val innerX = (fWidth - innerSize.width) / 2
    val innerY = (fHeight - innerSize.height) / 2
    val innerRect = Rect(innerX, innerY, innerX + innerSize.width, innerY + innerSize.height)
    path.arcTo(innerRect, innerEndAngle, innerStartAngle - innerEndAngle, false)

    // 5. Draw start inner bezier curve for rounded corners
    // Start inner rounded corner bezier start point
    cubicPoint1 = innerStartAngle.pointAtAngle(frameSize, innerRadius)

    // Start inner rounded corner bezier first control point
    val startInnerArcTangent = (innerStartAngle - 90F).unitVectorFromAngle()
    control1 = cubicPoint1 + startInnerArcTangent * innerTangentOffset

    // Start inner rounded corner end point
    val startAngleVector = startAngle.unitVectorFromAngle()
    val startInnerCornerPoint = startAngle.pointAtAngle(frameSize, innerRadius)
    cubicPoint2 = startInnerCornerPoint + startAngleVector * innerCircumferenceOffset

    // Start inner rounded corner bezier second control point
    control2 = cubicPoint2 - startAngleVector * innerTangentOffset
    path.cubicTo(control1.x, control1.y, control2.x, control2.y, cubicPoint2.x, cubicPoint2.y)

    // 6. Draw connecting line and start outer bezier curve for rounded corners
    // start outer rounded corner bezier start point
    val startOuterCornerPoint = startAngle.pointAtAngle(frameSize, outerRadius)
    cubicPoint1 = startOuterCornerPoint - startAngleVector * outerCircumferenceOffset

    // start outer rounded corner bezier first control point
    control1 = cubicPoint1 + startAngleVector * outerTangentOffset

    // start outer rounded corner end point
    cubicPoint2 = outerStartAngle.pointAtAngle(frameSize, outerRadius)

    // start outer rounded corner bezier second control point
    val startOuterArcTangent = (outerStartAngle - 90F).unitVectorFromAngle()
    control2 = cubicPoint2 + startOuterArcTangent * outerTangentOffset
    path.cubicTo(control1.x, control1.y, control2.x, control2.y, cubicPoint2.x, cubicPoint2.y)

    path.close()
    return Pair(listOf(path), listOf())
}

private class Insets(val top: Float, val left: Float, val bottom: Float, val right: Float) {
    companion object {
        fun uniform(inset: Float): Insets {
            return Insets(top = inset, left = inset, bottom = inset, right = inset)
        }
    }
}

/// Offset a round rect outwards or inwards based on the inset and amount.
/// Zero-radius corners are preserved when outsetting.
private fun RoundRect.offset(insets: Insets, amount: Float): RoundRect {
    return RoundRect(
        left = left - insets.left * amount,
        top = top - insets.top * amount,
        right = right + insets.right * amount,
        bottom = bottom + insets.bottom * amount,
        topLeftCornerRadius = topLeftCornerRadius.offset(insets.left, insets.top, amount),
        topRightCornerRadius = topRightCornerRadius.offset(insets.right, insets.top, amount),
        bottomLeftCornerRadius = bottomLeftCornerRadius.offset(insets.left, insets.bottom, amount),
        bottomRightCornerRadius =
            bottomRightCornerRadius.offset(insets.right, insets.bottom, amount),
    )
}

private fun CornerRadius.offset(offX: Float, offY: Float, amount: Float): CornerRadius {
    // Special case a square corner radius; it remains square even when outset.
    if (x <= 0.0f && y <= 0.0f) {
        return this
    }
    // Special casing around zero; we offset by the least of offX and offY unless
    // one of them is zero.
    val (coercedOffX, coercedOffY) =
        if (amount > 0.0f) {
            val off =
                if (offX <= 0.0f || offY <= 0.0f) {
                    offX.coerceAtLeast(offY)
                } else {
                    offX.coerceAtMost(offY)
                }
            Pair(off, off)
        } else {
            Pair(offX, offY)
        }

    return CornerRadius(
        x = (x + coercedOffX * amount).coerceAtLeast(0.0f),
        y = (y + coercedOffY * amount).coerceAtLeast(0.0f),
    )
}

private fun Path.addRoundRect(roundRect: RoundRect, dir: android.graphics.Path.Direction) {
    val rectF = android.graphics.RectF()
    val radii = FloatArray(8)
    rectF.set(roundRect.left, roundRect.top, roundRect.right, roundRect.bottom)
    radii[0] = roundRect.topLeftCornerRadius.x
    radii[1] = roundRect.topLeftCornerRadius.y

    radii[2] = roundRect.topRightCornerRadius.x
    radii[3] = roundRect.topRightCornerRadius.y

    radii[4] = roundRect.bottomRightCornerRadius.x
    radii[5] = roundRect.bottomRightCornerRadius.y

    radii[6] = roundRect.bottomLeftCornerRadius.x
    radii[7] = roundRect.bottomLeftCornerRadius.y

    asAndroidPath().addRoundRect(rectF, radii, dir)
}

/// Compute the ComputedPaths for a ViewShape that is a rounded rect using
/// simple geometry operations; this method implements individual border
/// widths for each side, which the generic version doesn't (because
/// arbitrary paths don't have "sides").
///
/// This method also doesn't use as many path operations, so it may execute
/// faster.
private fun computeRoundRectPathsFast(
    style: ViewStyle,
    cornerRadius: FloatArray,
    density: Float,
    frameSize: Size,
): ComputedPaths {
    val r =
        RoundRect(
            0.0f,
            0.0f,
            frameSize.width,
            frameSize.height,
            CornerRadius(cornerRadius[0] * density, cornerRadius[0] * density),
            CornerRadius(cornerRadius[1] * density, cornerRadius[1] * density),
            CornerRadius(cornerRadius[2] * density, cornerRadius[2] * density),
            CornerRadius(cornerRadius[3] * density, cornerRadius[3] * density),
        )

    val strokeInsets =
        Insets(
            top = style.node_style.stroke.stroke_weight.top() * density,
            left = style.node_style.stroke.stroke_weight.left() * density,
            bottom = style.node_style.stroke.stroke_weight.bottom() * density,
            right = style.node_style.stroke.stroke_weight.right() * density,
        )

    // We can generate the fill path directly from `r`, and can make the stroke
    // path by offsetting `r` along the stroke insets.
    val fill = Path()
    fill.addRoundRect(r)
    val fills = listOf(fill)

    val insetAmount =
        when (strokeAlignFromInt(style.node_style.stroke.stroke_align)) {
            StrokeAlignType.Center -> -0.5f
            StrokeAlignType.Inside -> -1.0f
            StrokeAlignType.Outside -> 0.0f
            else -> -0.5f
        }
    val stroke = Path()
    val interior = r.offset(strokeInsets, insetAmount)
    val exterior = r.offset(strokeInsets, 1.0f + insetAmount)
    stroke.addRoundRect(exterior, android.graphics.Path.Direction.CCW)
    stroke.addRoundRect(interior, android.graphics.Path.Direction.CW)
    val strokes = listOf(stroke)

    // The shadow clip is simply the exterior path.
    val shadowClip = Path()
    shadowClip.addRoundRect(exterior)
    val shadowClips = listOf(shadowClip)

    // Generate the shadow descriptions from the style.
    val shadows =
        style.node_style.box_shadow.mapNotNull { shadow ->
            when (val shadowbox = shadow.shadow_box.get()) {
                is ShadowBox.Inset -> {
                    val insetShadowInset = Insets.uniform(shadowbox.value.spread_radius * density)
                    val insetShadowRect = exterior.offset(insetShadowInset, -1.0f)
                    val insetShadowPath = Path()
                    insetShadowPath.addRoundRect(
                        insetShadowRect,
                        android.graphics.Path.Direction.CW,
                    )
                    insetShadowPath.asAndroidPath().fillType =
                        android.graphics.Path.FillType.INVERSE_EVEN_ODD
                    ComputedShadow(listOf(insetShadowPath), shadow)
                }
                is ShadowBox.Outset -> {
                    val boxShadowOutset = Insets.uniform(shadowbox.value.spread_radius * density)
                    val boxShadowRect = exterior.offset(boxShadowOutset, 1.0f)
                    val boxShadowPath = Path()
                    boxShadowPath.addRoundRect(boxShadowRect)
                    ComputedShadow(listOf(boxShadowPath), shadow)
                }
                else -> {
                    null
                }
            }
        }

    return ComputedPaths(fills, strokes, shadowClips, shadows, null)
}
