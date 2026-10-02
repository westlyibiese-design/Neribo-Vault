package com.westly.neribovault.core.ui.logo

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val LogoBackground = Color(0xFF141414)
private val WordmarkColor = Color(0xFFF1F0EC)
private val TaglineColor = Color(0xFFC3C3B8)

private val RingStart = Color(0xFFE5943A)
private val RingMid = Color(0xFFCD7A2E)
private val RingEnd = Color(0xFF9C431E)
private val SideArrowColor = Color(0xFFD95C33)
private val MiddleArrowColor = Color(0xFFEFA029)

// Reference geometry (SVG viewBox 270 170 660 560).
private const val VIEW_X = 270f
private const val VIEW_Y = 170f
private const val VIEW_W = 660f
private const val VIEW_H = 560f
private const val RING_CENTER_X = 540f
private const val RING_CENTER_Y = 447f
private const val RING_RADIUS = 202.5f
private const val RING_STROKE = 67f
private const val RING_START_ANGLE = -61f
private const val RING_SWEEP = -238f
private const val MIDDLE_PIVOT_X = 655f
private const val MIDDLE_PIVOT_Y = 447f

private fun trianglePath(
    x1: Float, y1: Float,
    x2: Float, y2: Float,
    x3: Float, y3: Float,
): Path = Path().apply {
    moveTo(x1, y1)
    lineTo(x2, y2)
    lineTo(x3, y3)
    close()
}

/** True when the person has turned system animations off. */
private fun animationsAreOff(context: android.content.Context): Boolean {
    val scale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    return scale == 0f
}

/**
 * The animated Neribo Vault logo: icon, "Neribo Vault" and "© NERIBO GROUP" on a fixed
 * dark background. It plays once and then calls [onFinished] exactly once. It ignores the
 * app theme.
 */
@Composable
fun NeriboLogoReveal(
    modifier: Modifier = Modifier,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val reducedMotion = remember { animationsAreOff(context) }
    val elapsed = remember {
        Animatable(if (reducedMotion) LogoTimeline.TOTAL_MS else 0f)
    }
    val latestOnFinished by rememberUpdatedState(onFinished)
    var finishedReported by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (reducedMotion) {
            delay(LogoTimeline.REDUCED_MOTION_HOLD_MS)
        } else {
            elapsed.animateTo(
                targetValue = LogoTimeline.TOTAL_MS,
                animationSpec = tween(
                    durationMillis = LogoTimeline.TOTAL_MS.toInt(),
                    easing = LinearEasing,
                ),
            )
            delay(LogoTimeline.HOLD_AFTER_MS)
        }
        if (!finishedReported) {
            finishedReported = true
            latestOnFinished()
        }
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val iconWidth = minOf(screenWidthDp * 0.55f, 240f).dp
    val iconHeight = iconWidth * (VIEW_H / VIEW_W)
    val shineEnabled = !reducedMotion

    val ringBrush = remember {
        Brush.linearGradient(
            colorStops = arrayOf(
                0.00f to RingStart,
                0.55f to RingMid,
                1.00f to RingEnd,
            ),
            start = Offset(397.6f, 276.9f),
            end = Offset(572.1f, 617.1f),
        )
    }
    val topArrow = remember { trianglePath(655f, 296f, 655f, 396f, 822f, 346f) }
    val bottomArrow = remember { trianglePath(655f, 497f, 655f, 598f, 822f, 548f) }
    val middleArrow = remember { trianglePath(655f, 373f, 655f, 520f, 898f, 447f) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LogoBackground),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(width = iconWidth, height = iconHeight)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val now = elapsed.value
            val unit = size.width / VIEW_W
            withTransform({
                scale(unit, unit, pivot = Offset.Zero)
                translate(-VIEW_X, -VIEW_Y)
            }) {
                // 1. Ring.
                val ringProgress = LogoTimeline.progress(
                    now, LogoTimeline.RING_START, LogoTimeline.RING_DURATION, LogoTimeline.RING_EASING,
                )
                if (ringProgress > 0f) {
                    drawArc(
                        brush = ringBrush,
                        startAngle = RING_START_ANGLE,
                        sweepAngle = RING_SWEEP * ringProgress,
                        useCenter = false,
                        topLeft = Offset(RING_CENTER_X - RING_RADIUS, RING_CENTER_Y - RING_RADIUS),
                        size = Size(RING_RADIUS * 2f, RING_RADIUS * 2f),
                        style = Stroke(width = RING_STROKE, cap = StrokeCap.Round),
                    )
                }

                // 2 and 3. Side arrows.
                val topEased = LogoTimeline.progressOvershoot(
                    now, LogoTimeline.TOP_START, LogoTimeline.SIDE_DURATION, LogoTimeline.SIDE_EASING,
                )
                val topAlpha = topEased.coerceIn(0f, 1f)
                if (topAlpha > 0f) {
                    withTransform({
                        translate((1f - topEased) * LogoTimeline.SIDE_SLIDE_UNITS, 0f)
                    }) {
                        drawPath(topArrow, SideArrowColor, alpha = topAlpha)
                    }
                }
                val bottomEased = LogoTimeline.progressOvershoot(
                    now, LogoTimeline.BOTTOM_START, LogoTimeline.SIDE_DURATION, LogoTimeline.SIDE_EASING,
                )
                val bottomAlpha = bottomEased.coerceIn(0f, 1f)
                if (bottomAlpha > 0f) {
                    withTransform({
                        translate((1f - bottomEased) * LogoTimeline.SIDE_SLIDE_UNITS, 0f)
                    }) {
                        drawPath(bottomArrow, SideArrowColor, alpha = bottomAlpha)
                    }
                }

                // 4. Middle arrow: scales from its fixed left edge.
                val middleScale = LogoTimeline.progress(
                    now, LogoTimeline.MIDDLE_START, LogoTimeline.MIDDLE_DURATION, LogoTimeline.MIDDLE_EASING,
                )
                if (middleScale > 0.001f) {
                    val middleAlpha = (
                        LogoTimeline.fraction(
                            now, LogoTimeline.MIDDLE_START, LogoTimeline.MIDDLE_DURATION,
                        ) / LogoTimeline.MIDDLE_FADE_SHARE
                        ).coerceIn(0f, 1f)
                    withTransform({
                        scale(middleScale, 1f, pivot = Offset(MIDDLE_PIVOT_X, MIDDLE_PIVOT_Y))
                    }) {
                        drawPath(middleArrow, MiddleArrowColor, alpha = middleAlpha)
                    }
                }

                // Shine: only lights pixels already drawn (SrcAtop on the offscreen layer).
                if (shineEnabled) {
                    val shineFraction = LogoTimeline.fraction(
                        now, LogoTimeline.ICON_SHINE_START, LogoTimeline.ICON_SHINE_DURATION,
                    )
                    if (shineFraction > 0f && shineFraction < 1f) {
                        val shineProgress = LogoTimeline.progress(
                            now,
                            LogoTimeline.ICON_SHINE_START,
                            LogoTimeline.ICON_SHINE_DURATION,
                            LogoTimeline.ICON_SHINE_EASING,
                        )
                        val bandLeft = LogoTimeline.SHINE_FROM_X +
                            (LogoTimeline.SHINE_TO_X - LogoTimeline.SHINE_FROM_X) * shineProgress
                        val bandRight = bandLeft + LogoTimeline.SHINE_BAND_WIDTH
                        val band = Brush.horizontalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0f),
                                Color.White.copy(alpha = LogoTimeline.SHINE_PEAK_ALPHA),
                                Color.White.copy(alpha = 0f),
                            ),
                            startX = bandLeft,
                            endX = bandRight,
                        )
                        drawRect(
                            brush = band,
                            topLeft = Offset(bandLeft, VIEW_Y),
                            size = Size(LogoTimeline.SHINE_BAND_WIDTH, VIEW_H),
                            blendMode = BlendMode.SrcAtop,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = "Neribo Vault",
            color = WordmarkColor,
            fontSize = 34.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = (-0.34f).sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .graphicsLayer {
                    val p = LogoTimeline.progress(
                        elapsed.value,
                        LogoTimeline.WORDMARK_START,
                        LogoTimeline.WORDMARK_DURATION,
                        LogoTimeline.WORDMARK_EASING,
                    )
                    alpha = p
                    translationY = (1f - p) * LogoTimeline.WORDMARK_RISE_DP.dp.toPx()
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    if (shineEnabled) {
                        val fraction = LogoTimeline.fraction(
                            elapsed.value,
                            LogoTimeline.WORDMARK_SHINE_START,
                            LogoTimeline.WORDMARK_SHINE_DURATION,
                        )
                        if (fraction > 0f && fraction < 1f) {
                            val eased = LogoTimeline.progress(
                                elapsed.value,
                                LogoTimeline.WORDMARK_SHINE_START,
                                LogoTimeline.WORDMARK_SHINE_DURATION,
                                LogoTimeline.WORDMARK_SHINE_EASING,
                            )
                            val bandWidth = size.width * 0.4f
                            val bandLeft = -bandWidth + (size.width + bandWidth) * eased
                            val band = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0f),
                                    Color.White.copy(alpha = 0.9f),
                                    Color.White.copy(alpha = 0f),
                                ),
                                startX = bandLeft,
                                endX = bandLeft + bandWidth,
                            )
                            drawRect(
                                brush = band,
                                topLeft = Offset(bandLeft, 0f),
                                size = Size(bandWidth, size.height),
                                blendMode = BlendMode.SrcAtop,
                            )
                        }
                    }
                },
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = "© NERIBO GROUP",
            color = TaglineColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 1.8.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                alpha = LogoTimeline.progress(
                    elapsed.value,
                    LogoTimeline.TAGLINE_START,
                    LogoTimeline.TAGLINE_DURATION,
                    LogoTimeline.TAGLINE_EASING,
                )
            },
        )
    }
}
