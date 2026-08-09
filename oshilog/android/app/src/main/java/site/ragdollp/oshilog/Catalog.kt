package site.ragdollp.oshilog

/**
 * アプリ内蔵の検索用カタログ（オフライン）。
 * ※ 外部APIではなく同梱データ。編集・追加はここに項目を足すだけ。
 *    将来オンライン検索に差し替える場合は search() の中身を API 呼び出しに変更する。
 */

data class CatalogArtist(val name: String, val kana: String, val group: String = "")
data class CatalogFest(val name: String, val kana: String, val venue: String)

// 検索結果（アーティスト or フェス を統一的に扱う）
sealed class SearchHit {
    data class Artist(val a: CatalogArtist) : SearchHit()
    data class Fest(val f: CatalogFest) : SearchHit()
}

object Catalog {

    val artists = listOf(
        CatalogArtist("YOASOBI", "よあそび ヨアソビ yoasobi"),
        CatalogArtist("Mrs. GREEN APPLE", "みせすぐりーんあっぷる ミセスグリーンアップル mrs green apple"),
        CatalogArtist("Official髭男dism", "おふぃしゃるひげだんでぃずむ ひげだん ヒゲダン higedan"),
        CatalogArtist("あいみょん", "あいみょん aimyon"),
        CatalogArtist("King Gnu", "きんぐぬー キングヌー king gnu"),
        CatalogArtist("米津玄師", "よねづけんし ヨネヅケンシ kenshi yonezu"),
        CatalogArtist("Vaundy", "ばうんでぃ バウンディ vaundy"),
        CatalogArtist("back number", "ばっくなんばー バックナンバー back number"),
        CatalogArtist("Ado", "あど アド ado"),
        CatalogArtist("Superfly", "すーぱーふらい スーパーフライ superfly"),
        CatalogArtist("あいみょん", "あいみょん aimyon"),
        CatalogArtist("Perfume", "ぱふゅーむ パフューム perfume"),
        CatalogArtist("BABYMETAL", "べびーめたる ベビーメタル babymetal"),
        CatalogArtist("ONE OK ROCK", "わんおくろっく ワンオク oneokrock one ok rock"),
        CatalogArtist("RADWIMPS", "らっどうぃんぷす ラッドウィンプス radwimps"),
        CatalogArtist("[Alexandros]", "あれきさんどろす アレキサンドロス alexandros"),
        CatalogArtist("サカナクション", "さかなくしょん サカナクション sakanaction"),
        CatalogArtist("BUMP OF CHICKEN", "ばんぷおぶちきん バンプ bump of chicken"),
        CatalogArtist("スピッツ", "すぴっつ スピッツ spitz"),
        CatalogArtist("Mr.Children", "みすたーちるどれん ミスチル mr children mrchildren"),
        CatalogArtist("サザンオールスターズ", "さざんおーるすたーず サザン southern all stars"),
        CatalogArtist("あいみょん", "あいみょん aimyon"),
        CatalogArtist("宇多田ヒカル", "うただひかる ウタダヒカル hikaru utada"),
        CatalogArtist("星野源", "ほしのげん ホシノゲン gen hoshino"),
        CatalogArtist("藤井風", "ふじいかぜ フジイカゼ fujii kaze"),
        CatalogArtist("Superfly", "すーぱーふらい superfly"),
        CatalogArtist("LiSA", "りさ リサ lisa"),
        CatalogArtist("Aimer", "えめ エメ aimer"),
        CatalogArtist("緑黄色社会", "りょくしゃか リョクシャカ りょくおうしょくしゃかい ryokushaka"),
        CatalogArtist("SEKAI NO OWARI", "せかいのおわり セカオワ sekai no owari sekaowa"),
        CatalogArtist("UVERworld", "うーばーわーるど ウーバーワールド uverworld"),
        CatalogArtist("BE:FIRST", "びーふぁーすと ビーファースト befirst be first"),
        CatalogArtist("Snow Man", "すのーまん スノーマン snow man snowman"),
        CatalogArtist("SixTONES", "すとーんず ストーンズ sixtones"),
        CatalogArtist("なにわ男子", "なにわだんし ナニワダンシ naniwa danshi"),
        CatalogArtist("SEVENTEEN", "せぶんてぃーん セブチ seventeen"),
        CatalogArtist("TWICE", "とぅわいす トゥワイス twice"),
        CatalogArtist("NewJeans", "にゅーじーんず ニュージーンズ newjeans new jeans"),
        CatalogArtist("IVE", "あいぶ アイブ ive"),
        CatalogArtist("LE SSERAFIM", "るせらふぃむ ルセラフィム le sserafim lesserafim"),
        CatalogArtist("乃木坂46", "のぎざかふぉーてぃーしっくす ノギザカ nogizaka46 nogizaka"),
        CatalogArtist("櫻坂46", "さくらざか サクラザカ sakurazaka46 sakurazaka"),
        CatalogArtist("日向坂46", "ひなたざか ヒナタザカ hinatazaka46 hinatazaka"),
        CatalogArtist("欅坂46", "けやきざか ケヤキザカ keyakizaka46"),
        CatalogArtist("AKB48", "えーけーびー エーケービー akb48 akb"),
        CatalogArtist("Perfume", "ぱふゅーむ perfume"),
        CatalogArtist("東京事変", "とうきょうじへん トウキョウジヘン tokyo jihen"),
        CatalogArtist("あいみょん", "あいみょん aimyon")
    ).distinctBy { it.name }

    val fests = listOf(
        CatalogFest("FUJI ROCK FESTIVAL", "ふじろっく フジロック fuji rock", "苗場スキー場（新潟）"),
        CatalogFest("SUMMER SONIC", "さまーそにっく サマソニ summer sonic", "幕張メッセ / 万博記念公園"),
        CatalogFest("ROCK IN JAPAN FESTIVAL", "ろっきん ロッキン rock in japan", "蘇我スポーツ公園（千葉）"),
        CatalogFest("COUNTDOWN JAPAN", "かうんとだうんじゃぱん カウントダウンジャパン countdown japan cdj", "幕張メッセ"),
        CatalogFest("VIVA LA ROCK", "びばらろっく ビバラ viva la rock", "さいたまスーパーアリーナ"),
        CatalogFest("JAPAN JAM", "じゃぱんじゃむ ジャパンジャム japan jam", "蘇我スポーツ公園（千葉）"),
        CatalogFest("RISING SUN ROCK FESTIVAL", "らいじんぐさん ライジングサン rising sun", "石狩湾新港（北海道）"),
        CatalogFest("METROCK", "めとろっく メトロック metrock", "東京・大阪"),
        CatalogFest("SWEET LOVE SHOWER", "すうぃーとらぶしゃわー ラブシャ sweet love shower", "山中湖交流プラザ（山梨）"),
        CatalogFest("COMING KOBE", "かみんぐこうべ カミングコウベ coming kobe", "神戸"),
        CatalogFest("OTODAMA", "おとだま オトダマ otodama", "泉大津フェニックス（大阪）"),
        CatalogFest("MONSTER baSH", "もんすたーばっしゅ モンスターバッシュ monster bash", "国営讃岐まんのう公園（香川）"),
        CatalogFest("WILD BUNCH FEST", "わいるどばんち ワイルドバンチ wild bunch", "山口きらら博記念公園"),
        CatalogFest("ラブライブ！フェス", "らぶらいぶ ラブライブ love live", "各地アリーナ"),
        CatalogFest("アニメロサマーライブ", "あにめろさまーらいぶ アニサマ animelo summer live anisama", "さいたまスーパーアリーナ")
    ).distinctBy { it.name }

    /** アーティスト → フェス の順で、名前/読みの部分一致で検索。 */
    fun search(q: String, limit: Int = 40): List<SearchHit> {
        val query = q.trim().lowercase()
        if (query.isEmpty()) return emptyList()
        val a = artists.filter { it.name.lowercase().contains(query) || it.kana.lowercase().contains(query) }
            .map { SearchHit.Artist(it) }
        val f = fests.filter { it.name.lowercase().contains(query) || it.kana.lowercase().contains(query) }
            .map { SearchHit.Fest(it) }
        return (a + f).take(limit)
    }
}
