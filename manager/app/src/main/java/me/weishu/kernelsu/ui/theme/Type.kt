package me.weishu.kernelsu.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import me.weishu.kernelsu.R
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles

/**
 * Baloo 2 (OFL, license in assets/licenses): rounded and friendly, full Vietnamese coverage.
 * Static weights instanced from the variable font and subset to Latin + Vietnamese.
 */
val AppFontFamily = FontFamily(
    Font(R.font.baloo2_regular, FontWeight.Normal),
    Font(R.font.baloo2_medium, FontWeight.Medium),
    Font(R.font.baloo2_semibold, FontWeight.SemiBold),
    Font(R.font.baloo2_bold, FontWeight.Bold),
)

private fun TextStyle.withAppFont() = copy(fontFamily = AppFontFamily)

/** Miuix text styles with [AppFontFamily] applied to every role. */
val AppTextStyles: TextStyles = defaultTextStyles().let {
    defaultTextStyles(
        main = it.main.withAppFont(),
        paragraph = it.paragraph.withAppFont(),
        body1 = it.body1.withAppFont(),
        body2 = it.body2.withAppFont(),
        button = it.button.withAppFont(),
        footnote1 = it.footnote1.withAppFont(),
        footnote2 = it.footnote2.withAppFont(),
        headline1 = it.headline1.withAppFont(),
        headline2 = it.headline2.withAppFont(),
        subtitle = it.subtitle.withAppFont(),
        title1 = it.title1.withAppFont(),
        title2 = it.title2.withAppFont(),
        title3 = it.title3.withAppFont(),
        title4 = it.title4.withAppFont(),
    )
}
