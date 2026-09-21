package com.slackoff.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.slackoff.app.model.Episode
import com.slackoff.app.model.NovelBook
import com.slackoff.app.ui.MainScreen
import com.slackoff.app.ui.novel.NovelBookDetailScreen
import com.slackoff.app.ui.novel.NovelCacheListScreen
import com.slackoff.app.ui.novel.NovelListKind
import com.slackoff.app.ui.novel.NovelLocalListPayload
import com.slackoff.app.ui.novel.LocalNovelNavigator
import com.slackoff.app.ui.novel.NovelNavigator
import com.slackoff.app.ui.novel.NovelReaderScreen
import com.slackoff.app.ui.novel.NovelSearchScreen
import com.slackoff.app.ui.novel.NovelSettingsScreen
import com.slackoff.app.ui.novel.PagedBookListScreen
import com.slackoff.app.ui.novel.RanksScreen
import com.slackoff.app.ui.novel.LocalBookListScreen
import com.slackoff.app.ui.detail.VideoDetailScreen
import com.slackoff.app.ui.player.PlayerScreen
import com.slackoff.app.ui.theme.Theme
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Theme.Cream
            ) {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val activity = androidx.compose.ui.platform.LocalContext.current as? ComponentActivity
    var showExitConfirm by remember { mutableStateOf(false) }

    BackHandler(enabled = navController.currentBackStackEntryAsState().value?.destination?.route == "main") {
        showExitConfirm = true
    }

    // 书名 / 作者 / 分类可能为 null，一律走可选查询参数：
    // 放进路径段会变成空段（novel/book/a/b/c//），Navigation Compose 匹配不上。
    val encode: (String?) -> String = { URLEncoder.encode(it ?: "", "UTF-8") }
    val decode: (String?) -> String? = { raw ->
        val value = raw?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
        value.ifEmpty { null }
    }

    val novelNavigator = NovelNavigator(
        openBook = { source, bookId, title, author, category ->
            navController.navigate(
                "novel/book/$source/$bookId/${encode(title)}?author=${encode(author)}&category=${encode(category)}"
            )
        },
        openReader = { source, bookId, title, author, category, chapterId ->
            navController.navigate(
                "novel/reader/$source/$bookId/${encode(title)}" +
                    "?author=${encode(author)}&category=${encode(category)}&chapterId=${encode(chapterId)}"
            )
        },
        openCategory = { slug, name, source ->
            navController.navigate("novel/category/$slug/${encode(name)}/$source")
        },
        openRanks = { source -> navController.navigate("novel/ranks/$source") },
        openFull = { source -> navController.navigate("novel/full/$source") },
        openLocalList = { title, books ->
            NovelLocalListPayload.set(title, books)
            navController.navigate("novel/localList/${encode(title)}")
        },
        openSearch = { navController.navigate("novel/search") },
    )

    // Provider 必须包住整个 NavHost：novel/ 下的详情页、搜索页等是独立 destination，
    // 只包 "main" 那一支的话，它们读 LocalNovelNavigator 会命中 staticCompositionLocalOf
    // 的 error() 默认值 —— 表现为「点任意一本书立刻崩溃」。
    CompositionLocalProvider(LocalNovelNavigator provides novelNavigator) {
        NavHost(navController = navController, startDestination = "main") {

            composable("main") {
                MainScreen(
                    onNavigateToDetail = { slug, title ->
                        navController.navigate("detail/${URLEncoder.encode(slug, "UTF-8")}/${URLEncoder.encode(title, "UTF-8")}")
                    },
                    novelNavigator = novelNavigator,
                    onOpenNovelSettings = { navController.navigate("novel/settings") },
                )
            }

            composable(
                "detail/{slug}/{title}",
                arguments = listOf(
                    navArgument("slug") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val slug = backStackEntry.arguments?.getString("slug") ?: ""
                val title = backStackEntry.arguments?.getString("title") ?: ""
                VideoDetailScreen(
                    slug = slug,
                    title = title,
                    onBack = { navController.popBackStack() },
                    onNavigateToPlayer = { s, ep, t, eps ->
                        val episodesJson = URLEncoder.encode(
                            Json.encodeToString(eps),
                            "UTF-8"
                        )
                        navController.navigate("player/$s/$ep/${URLEncoder.encode(t, "UTF-8")}/$episodesJson")
                    }
                )
            }

            composable(
                "player/{slug}/{episode}/{title}/{episodesJson}",
                arguments = listOf(
                    navArgument("slug") { type = NavType.StringType },
                    navArgument("episode") { type = NavType.IntType },
                    navArgument("title") { type = NavType.StringType },
                    navArgument("episodesJson") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val slug = backStackEntry.arguments?.getString("slug") ?: ""
                val episode = backStackEntry.arguments?.getInt("episode") ?: 1
                val title = URLDecoder.decode(
                    backStackEntry.arguments?.getString("title") ?: "",
                    "UTF-8"
                )
                val episodesJson = URLDecoder.decode(
                    backStackEntry.arguments?.getString("episodesJson") ?: "[]",
                    "UTF-8"
                )
                val episodes = try {
                    Json.decodeFromString<List<Episode>>(episodesJson)
                } catch (e: Exception) {
                    emptyList()
                }

                PlayerScreen(
                    slug = slug,
                    episode = episode,
                    title = title,
                    episodes = episodes,
                    onBack = { navController.popBackStack() }
                )
            }

            // MARK: 小说频道

            composable(
                route = "novel/book/{source}/{bookId}/{title}?author={author}&category={category}",
                arguments = listOf(
                    navArgument("source") { type = NavType.StringType },
                    navArgument("bookId") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType },
                    navArgument("author") { type = NavType.StringType; defaultValue = "" },
                    navArgument("category") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val args = entry.arguments
                NovelBookDetailScreen(
                    source = args?.getString("source") ?: "",
                    bookId = args?.getString("bookId") ?: "",
                    title = decode(args?.getString("title")) ?: "",
                    author = decode(args?.getString("author")),
                    category = decode(args?.getString("category")),
                )
            }

            composable(
                route = "novel/reader/{source}/{bookId}/{title}?author={author}&category={category}&chapterId={chapterId}",
                arguments = listOf(
                    navArgument("source") { type = NavType.StringType },
                    navArgument("bookId") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType },
                    navArgument("author") { type = NavType.StringType; defaultValue = "" },
                    navArgument("category") { type = NavType.StringType; defaultValue = "" },
                    navArgument("chapterId") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val args = entry.arguments
                NovelReaderScreen(
                    source = args?.getString("source") ?: "",
                    bookId = args?.getString("bookId") ?: "",
                    title = decode(args?.getString("title")) ?: "",
                    author = decode(args?.getString("author")),
                    category = decode(args?.getString("category")),
                    chapterId = decode(args?.getString("chapterId")),
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = "novel/category/{slug}/{name}/{source}",
                arguments = listOf(
                    navArgument("slug") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType },
                    navArgument("source") { type = NavType.StringType },
                ),
            ) { entry ->
                val args = entry.arguments
                val slug = args?.getString("slug") ?: ""
                val name = decode(args?.getString("name")) ?: slug
                PagedBookListScreen(
                    kind = NovelListKind.Category(slug, name),
                    source = args?.getString("source") ?: "",
                )
            }

            composable(
                route = "novel/full/{source}",
                arguments = listOf(navArgument("source") { type = NavType.StringType }),
            ) { entry ->
                PagedBookListScreen(
                    kind = NovelListKind.Full,
                    source = entry.arguments?.getString("source") ?: "",
                )
            }

            composable(
                route = "novel/ranks/{source}",
                arguments = listOf(navArgument("source") { type = NavType.StringType }),
            ) { entry ->
                RanksScreen(source = entry.arguments?.getString("source") ?: "")
            }

            composable(
                route = "novel/search",
            ) {
                NovelSearchScreen()
            }

            composable(
                route = "novel/settings",
            ) {
                NovelSettingsScreen(onOpenCacheList = { navController.navigate("novel/cacheList") })
            }

            composable(route = "novel/cacheList") {
                NovelCacheListScreen { source, bookId, title, author, category ->
                    navController.navigate(
                        "novel/book/${encode(source)}/${encode(bookId)}/${encode(title)}" +
                            "?author=${encode(author)}&category=${encode(category)}"
                    )
                }
            }

            composable(
                route = "novel/localList/{title}",
                arguments = listOf(navArgument("title") { type = NavType.StringType }),
            ) { entry ->
                LocalBookListScreen(
                    title = decode(entry.arguments?.getString("title")) ?: "列表",
                    books = NovelLocalListPayload.books,
                )
            }
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("退出应用") },
            text = { Text("确定要退出追剧小铺吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    activity?.finish()
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("取消") }
            },
        )
    }
}