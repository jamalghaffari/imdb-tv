package com.persica.imdbtv

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val browseFragment = BrowseSupportFragment().apply {
            title = "ویترین ۲۵۰ فیلم برتر تاریخ سینما"
            headersState = BrowseSupportFragment.HEADERS_ENABLED
            isHeadersTransitionOnBackEnabled = true
            brandColor = 0xFF08090D.toInt()
            searchAffordanceColor = 0xFFF5C518.toInt()
        }

        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, browseFragment)
            .commit()

        setupRows(browseFragment)
    }

    private fun setupRows(fragment: BrowseSupportFragment) {
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        val cardPresenter = CardPresenter()

        // 1. Row: Top 250 Movies
        val top250Adapter = ArrayObjectAdapter(cardPresenter)
        top250Adapter.add(Movie("tt0111161", 1, "رستگاری در شاوشنک", "The Shawshank Redemption", 1994, 9.3, "درام", "Frank Darabont", "دو مرد زندانی در طول سال‌ها پیوند عمیقی برقرار می‌کنند...", "https://persicatheme.ir/imdb/posters/tt0111161.jpg", "https://vidsrc.to/embed/movie/tt0111161"))
        top250Adapter.add(Movie("tt0068646", 2, "پدرخوانده", "The Godfather", 1972, 9.2, "جنایی، درام", "Francis Ford Coppola", "پدر سالخورده یک سلسله جنایی سازمان‌یافته کنترل را منتقل می‌کند...", "https://persicatheme.ir/imdb/posters/tt0068646.jpg", "https://vidsrc.to/embed/movie/tt0068646"))
        top250Adapter.add(Movie("tt0468569", 3, "شوالیه تاریکی", "The Dark Knight", 2008, 9.0, "اکشن، جنایی", "Christopher Nolan", "بتمن با تهدید جوکر برای برهم زدن گاتهام مواجه می‌شود...", "https://persicatheme.ir/imdb/posters/tt0468569.jpg", "https://vidsrc.to/embed/movie/tt0468569"))
        top250Adapter.add(Movie("tt0108052", 6, "فهرست شیندلر", "Schindler's List", 1993, 9.0, "بیوگرافی، درام", "Steven Spielberg", "اسکار شیندلر جان بیش از هزار پناهنده را نجات می‌دهد...", "https://persicatheme.ir/imdb/posters/tt0108052.jpg", "https://vidsrc.to/embed/movie/tt0108052"))
        top250Adapter.add(Movie("tt0110912", 8, "داستان عامه‌پسند", "Pulp Fiction", 1994, 8.9, "جنایی، درام", "Quentin Tarantino", "زندگی دو آدمکش، یک بوکسور و همسر یک گانگستر به هم گره می‌خورد...", "https://persicatheme.ir/imdb/posters/tt0110912.jpg", "https://vidsrc.to/embed/movie/tt0110912"))

        val header1 = HeaderItem(0, "۲۵۰ فیلم برتر تاریخ سینما (IMDb Top 250)")
        rowsAdapter.add(ListRow(header1, top250Adapter))

        // 2. Row: Ehsan Mansoori's 110 Collection
        val mansooriAdapter = ArrayObjectAdapter(cardPresenter)
        mansooriAdapter.add(Movie("tt0075314", 25, "راننده تاکسی", "Taxi Driver", 1976, 8.2, "جنایی، درام", "Martin Scorsese", "یک کهنه‌سرباز در خیابان‌های نیویورک با فساد جامعه درگیر می‌شود...", "https://persicatheme.ir/imdb/posters/tt0075314.jpg", "https://vidsrc.to/embed/movie/tt0075314"))
        mansooriAdapter.add(Movie("tt0114814", 17, "مظنونین همیشگی", "The Usual Suspects", 1995, 8.5, "جنایی، مهیج", "Bryan Singer", "داستان مرموز قتل‌عام یک قایق و هویت کایزر شوزه...", "https://persicatheme.ir/imdb/posters/tt0114814.jpg", "https://vidsrc.to/embed/movie/tt0114814"))
        mansooriAdapter.add(Movie("tt0137523", 10, "باشگاه مشت‌زنی", "Fight Club", 1999, 8.8, "درام", "David Fincher", "یک کارمند بی‌خواب یک باشگاه مبارزه زیرزمینی تاسیس می‌کند...", "https://persicatheme.ir/imdb/posters/tt0137523.jpg", "https://vidsrc.to/embed/movie/tt0137523"))

        val header2 = HeaderItem(1, "کلکسیون ۱۱۰ فیلم احسان منصوری")
        rowsAdapter.add(ListRow(header2, mansooriAdapter))

        fragment.adapter = rowsAdapter

        fragment.onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Movie) {
                val intent = Intent(this, PlaybackActivity::class.java).apply {
                    putExtra("movie", item)
                }
                startActivity(intent)
            }
        }
    }
}
