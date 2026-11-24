package com.AARCNoa.zhiyou.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 项目实际用到的实心（Filled）图标，形状取自 Material Symbols Outlined Filled 24.2.1。
 *
 * 加图标的办法：从图标库挑好后照下面一行的形状补一条（名称、视角、路径），
 * 不要把整个图标库加回来 —— 全量库每个约 11MB dex。
 */
object FilledIcons {

    val Add: ImageVector by lazy {
        icon("add", 960f, "M440 520H200v-80h240v-240h80v240h240v80H520v240h-80v-240Z")
    }

    val Article: ImageVector by lazy {
        icon("article", 960f, "M200 840q-33 0 -56.5 -23.5T120 760v-560q0 -33 23.5 -56.5T200 120h560q33 0 56.5 23.5T840 200v560q0 33 -23.5 56.5T760 840H200Zm80 -160h280v-80H280v80Zm0 -160h400v-80H280v80Zm0 -160h400v-80H280v80Z")
    }

    val Auto_awesome: ImageVector by lazy {
        icon("auto_awesome", 24f, "M19 9l-1.25 -2.75L15 5l2.75 -1.25L19 1l1.25 2.75L23 5l-2.75 1.25Zm0 14l-1.25 -2.75L15 19l2.75 -1.25L19 15l1.25 2.75L23 19l-2.75 1.25ZM9 20l-2.5 -5.5L1 12l5.5 -2.5L9 4l2.5 5.5L17 12l-5.5 2.5Z")
    }

    val Bookmark: ImageVector by lazy {
        icon("bookmark", 960f, "M200 840v-640q0 -33 23.5 -56.5T280 120h400q33 0 56.5 23.5T760 200v640L480 720L200 840Z")
    }

    val Camera: ImageVector by lazy {
        icon("camera", 960f, "M448 335l140 -240q91 24 161.5 88T852 335H448ZM337 435L200 195q54 -53 125.5 -84T480 80q13 0 30 1.5t31 3.5L337 435ZM93 580q-6 -24 -9.5 -49T80 480q0 -71 23 -134t65 -115l202 349H93ZM373 865q-91 -24 -162 -88T108 625h403L373 865Zm107 15q-15 0 -30.5 -2T420 874l203 -349l137 240q-54 53 -125.5 84T480 880Zm312 -151L590 380h277q6 24 9.5 49t3.5 51q0 70 -24.5 134T792 729Z")
    }

    val Cancel: ImageVector by lazy {
        icon("cancel", 960f, "M336 680l144 -144l144 144l56 -56l-144 -144l144 -144l-56 -56l-144 144l-144 -144l-56 56l144 144l-144 144l56 56ZM480 880q-83 0 -156 -31.5T197 763q-54 -54 -85.5 -127T80 480q0 -83 31.5 -156T197 197q54 -54 127 -85.5T480 80q83 0 156 31.5T763 197q54 54 85.5 127T880 480q0 83 -31.5 156T763 763q-54 54 -127 85.5T480 880Z")
    }

    val Check_circle: ImageVector by lazy {
        icon("check_circle", 960f, "M424 664l282 -282l-56 -56l-226 226l-114 -114l-56 56l170 170Zm56 216q-83 0 -156 -31.5T197 763q-54 -54 -85.5 -127T80 480q0 -83 31.5 -156T197 197q54 -54 127 -85.5T480 80q83 0 156 31.5T763 197q54 54 85.5 127T880 480q0 83 -31.5 156T763 763q-54 54 -127 85.5T480 880Z")
    }

    val Close: ImageVector by lazy {
        icon("close", 960f, "M256 760l-56 -56l224 -224l-224 -224l56 -56l224 224l224 -224l56 56l-224 224l224 224l-56 56l-224 -224l-224 224Z")
    }

    val Contacts: ImageVector by lazy {
        icon("contacts", 960f, "M160 920v-80h640v80H160Zm0 -800v-80h640v80H160Zm320 400q50 0 85 -35t35 -85q0 -50 -35 -85t-85 -35q-50 0 -85 35t-35 85q0 50 35 85t85 35ZM160 800q-33 0 -56.5 -23.5T80 720v-480q0 -33 23.5 -56.5T160 160h640q33 0 56.5 23.5T880 240v480q0 33 -23.5 56.5T800 800H160Zm70 -80h500q-45 -56 -109 -88t-141 -32q-77 0 -141 32t-109 88Z")
    }

    val Explore: ImageVector by lazy {
        icon("explore", 960f, "M260 700l300 -140l140 -300l-300 140l-140 300Zm220 -180q-17 0 -28.5 -11.5T440 480q0 -17 11.5 -28.5T480 440q17 0 28.5 11.5T520 480q0 17 -11.5 28.5T480 520Zm0 360q-83 0 -156 -31.5T197 763q-54 -54 -85.5 -127T80 480q0 -83 31.5 -156T197 197q54 -54 127 -85.5T480 80q83 0 156 31.5T763 197q54 54 85.5 127T880 480q0 83 -31.5 156T763 763q-54 54 -127 85.5T480 880Z")
    }

    val Extension: ImageVector by lazy {
        icon("extension", 960f, "M352 840H200q-33 0 -56.5 -23.5T120 760v-152q48 0 84 -30.5t36 -77.5q0 -47 -36 -77.5T120 392v-152q0 -33 23.5 -56.5T200 160h160q0 -42 29 -71t71 -29q42 0 71 29t29 71h160q33 0 56.5 23.5T800 240v160q42 0 71 29t29 71q0 42 -29 71t-71 29v160q0 33 -23.5 56.5T720 840H568q0 -50 -31.5 -85T460 720q-45 0 -76.5 35T352 840Z")
    }

    val Favorite: ImageVector by lazy {
        icon("favorite", 960f, "M480 840l-58 -52q-101 -91 -167 -157T150 512.5Q111 460 95.5 416T80 326q0 -94 63 -157t157 -63q52 0 99 22t81 62q34 -40 81 -62t99 -22q94 0 157 63t63 157q0 46 -15.5 90T810 512.5Q771 565 705 631T538 788l-58 52Z")
    }

    val Home: ImageVector by lazy {
        icon("home", 960f, "M160 840v-480l320 -240l320 240v480H560v-280H400v280H160Z")
    }

    val Mark_chat_read: ImageVector by lazy {
        icon("mark_chat_read", 960f, "M694 800L553 658l56 -56l85 85l170 -170l56 57l-226 226ZM80 880v-720q0 -33 23.5 -56.5T160 80h640q33 0 56.5 23.5T880 160v280H480v280H240L80 880Z")
    }

    val More_vert: ImageVector by lazy {
        icon("more_vert", 960f, "M480 800q-33 0 -56.5 -23.5T400 720q0 -33 23.5 -56.5T480 640q33 0 56.5 23.5T560 720q0 33 -23.5 56.5T480 800Zm0 -240q-33 0 -56.5 -23.5T400 480q0 -33 23.5 -56.5T480 400q33 0 56.5 23.5T560 480q0 33 -23.5 56.5T480 560Zm0 -240q-33 0 -56.5 -23.5T400 240q0 -33 23.5 -56.5T480 160q33 0 56.5 23.5T560 240q0 33 -23.5 56.5T480 320Z")
    }

    val Movie: ImageVector by lazy {
        icon("movie", 960f, "M160 160l80 160h120l-80 -160h80l80 160h120l-80 -160h80l80 160h120l-80 -160h120q33 0 56.5 23.5T880 240v480q0 33 -23.5 56.5T800 800H160q-33 0 -56.5 -23.5T80 720v-480q0 -33 23.5 -56.5T160 160Z")
    }

    val Person: ImageVector by lazy {
        icon("person", 960f, "M480 480q-66 0 -113 -47t-47 -113q0 -66 47 -113t113 -47q66 0 113 47t47 113q0 66 -47 113t-113 47ZM160 800v-112q0 -34 17.5 -62.5T224 582q62 -31 126 -46.5T480 520q66 0 130 15.5T736 582q29 15 46.5 43.5T800 688v112H160Z")
    }

    val Qr_code_scanner: ImageVector by lazy {
        icon("qr_code_scanner", 960f, "M80 280v-200h200v80H160v120H80Zm0 600v-200h80v120h120v80H80Zm600 0v-80h120v-120h80v200H680Zm120 -600v-120H680v-80h200v200h-80ZM700 700h60v60h-60v-60Zm0 -120h60v60h-60v-60Zm-60 60h60v60h-60v-60Zm-60 60h60v60h-60v-60Zm-60 -60h60v60h-60v-60Zm120 -120h60v60h-60v-60Zm-60 60h60v60h-60v-60Zm-60 -60h60v60h-60v-60Zm240 -320v240H520v-240h240ZM440 520v240H200v-240h240Zm0 -320v240H200v-240h240Zm-60 500v-120H260v120h120Zm0 -320v-120H260v120h120Zm320 0v-120H580v120h120Z")
    }

    val Settings: ImageVector by lazy {
        icon("settings", 960f, "M370 880l-16 -128q-13 -5 -24.5 -12T307 725l-119 50L78 585l103 -78q-1 -7 -1 -13.5v-27q0 -6.5 1 -13.5L78 375l110 -190l119 50q11 -8 23 -15t24 -12l16 -128h220l16 128q13 5 24.5 12t22.5 15l119 -50l110 190l-103 78q1 7 1 13.5v27q0 6.5 -2 13.5l103 78l-110 190l-118 -50q-11 8 -23 15t-24 12L590 880H370Zm112 -260q58 0 99 -41t41 -99q0 -58 -41 -99t-99 -41q-59 0 -99.5 41T342 480q0 58 40.5 99t99.5 41Z")
    }

    val Update: ImageVector by lazy {
        icon("update", 960f, "M480 840q-75 0 -140.5 -28.5t-114 -77q-48.5 -48.5 -77 -114T120 480q0 -75 28.5 -140.5t77 -114q48.5 -48.5 114 -77T480 120q82 0 155.5 35T760 254v-94h80v240H600v-80h110q-41 -56 -101 -88t-129 -32q-117 0 -198.5 81.5T200 480q0 117 81.5 198.5T480 760q105 0 183.5 -68T756 520h82q-15 137 -117.5 228.5T480 840Zm112 -192L440 496v-216h80v184l128 128l-56 56Z")
    }

    val Visibility: ImageVector by lazy {
        icon("visibility", 960f, "M480 640q75 0 127.5 -52.5T660 460q0 -75 -52.5 -127.5T480 280q-75 0 -127.5 52.5T300 460q0 75 52.5 127.5T480 640Zm0 -72q-45 0 -76.5 -31.5T372 460q0 -45 31.5 -76.5T480 352q45 0 76.5 31.5T588 460q0 45 -31.5 76.5T480 568Zm0 192q-146 0 -266 -81.5T40 460q54 -137 174 -218.5T480 160q146 0 266 81.5T920 460q-54 137 -174 218.5T480 760Z")
    }

    val Visibility_off: ImageVector by lazy {
        icon("visibility_off", 960f, "M792 904L624 738q-35 11 -70.5 16.5T480 760q-151 0 -269 -83.5T40 460q21 -53 53 -98.5t73 -81.5L56 168l56 -56l736 736l-56 56ZM480 640q11 0 20.5 -1t20.5 -4L305 419q-3 11 -4 20.5t-1 20.5q0 75 52.5 127.5T480 640Zm292 18L645 532q7 -17 11 -34.5t4 -37.5q0 -75 -52.5 -127.5T480 280q-20 0 -37.5 4T408 296L306 194q41 -17 84 -25.5t90 -8.5q151 0 269 83.5T920 460q-23 59 -60.5 109.5T772 658ZM587 474L467 354q28 -5 51.5 4.5T559 386q17 18 24.5 41.5T587 474Z")
    }

    val Wallet: ImageVector by lazy {
        icon("wallet", 960f, "M240 800q-66 0 -113 -47T80 640v-320q0 -66 47 -113t113 -47h480q66 0 113 47t47 113v320q0 66 -47 113t-113 47H240Zm0 -480h480q22 0 42 5t38 16v-21q0 -33 -23.5 -56.5T720 240H240q-33 0 -56.5 23.5T160 320v21q18 -11 38 -16t42 -5Zm-74 130l445 108q9 2 18 0t17 -8l139 -116q-11 -15 -28 -24.5t-37 -9.5H240q-26 0 -45.5 13.5T166 450Z")
    }

    val Warning: ImageVector by lazy {
        icon("warning", 960f, "M40 840l440 -760l440 760H40Zm440 -120q17 0 28.5 -11.5T520 680q0 -17 -11.5 -28.5T480 640q-17 0 -28.5 11.5T440 680q0 17 11.5 28.5T480 720Zm-40 -120h80v-200h-80v200Z")
    }
}

/**
 * 图形默认 24dp；[viewport] 是路径坐标系，Material Symbols 新库是 960，个别早期图标是 24。
 * 填充色固定黑色：调用方一律通过 `Icon(tint = …)` 上色，这里只提供形状。
 */
private fun icon(name: String, viewport: Float, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = viewport,
        viewportHeight = viewport,
    ).addPath(PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black)).build()
