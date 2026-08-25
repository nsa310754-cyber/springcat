package site.ragdollp.pocketzip

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * ツリー表示に必要な 4 アイコンだけを自前のベクターとして持つ。
 * androidx.compose.material:material-icons-extended (数千アイコン分、数十MB) を
 * まるごと同梱すると APK が肥大化するため、必要な図形だけを直書きしている。
 * パスデータは Material Design Icons (Apache License 2.0) 由来。
 */
private fun vectorIcon(name: String, svgPath: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(svgPath).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()

val IconChevronRight: ImageVector by lazy {
    vectorIcon("ChevronRight", "M8.59 16.59L13.17 12 8.59 7.41 10 6l6 6-6 6z")
}

val IconExpandMore: ImageVector by lazy {
    vectorIcon("ExpandMore", "M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z")
}

val IconFolder: ImageVector by lazy {
    vectorIcon(
        "Folder",
        "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"
    )
}

val IconFile: ImageVector by lazy {
    vectorIcon(
        "InsertDriveFile",
        "M6 2c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6H6zm7 7V3.5L18.5 9H13z"
    )
}
