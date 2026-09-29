package com.gatorforge.sidecar.trackb

import android.content.Context

/**
 * Hardware-independent v5 groundwork. This describes independent stored collections without
 * changing the v4 catalog or emitting any wire opcodes.
 */
internal object SidecarCollections {
    const val MAX_COLLECTION_PAGES = 16
    const val MAX_ACTIVE_COLLECTIONS = 16
    const val MAX_TOTAL_PAGES = 64
    const val MAX_TRANSACTION_PAGES = 4

    enum class RefreshClass { STATIC, PER_CYCLE, FIVE_MINUTES, HOURLY, DAILY }

    data class Descriptor(
        val id: Int,
        val module: Module,
        val name: String,
        val order: Int,
        val refreshClass: RefreshClass
    )

    data class PageTransaction(val collectionId: Int, val startPage: Int, val pageCount: Int) {
        init {
            require(collectionId in 1..0xffff)
            require(startPage >= 0)
            require(pageCount in 1..MAX_TRANSACTION_PAGES)
        }
    }

    data class Plan(
        val descriptor: Descriptor,
        val pageCount: Int,
        val transactions: List<PageTransaction>
    )

    // IDs and order are persisted protocol identities. Append new entries; never renumber these.
    val catalog: List<Descriptor> = listOf(
        Descriptor(0x0001, NoteModule, "Notes", 10, RefreshClass.STATIC),
        Descriptor(0x0002, AstronomyModule, "Astronomy", 20, RefreshClass.DAILY),
        Descriptor(0x0003, CalendarModule, "Calendar", 30, RefreshClass.PER_CYCLE),
        Descriptor(0x0004, WeatherModule, "Weather", 40, RefreshClass.HOURLY),
        Descriptor(0x0005, HeadlinesModule, "News", 50, RefreshClass.FIVE_MINUTES),
        Descriptor(0x0006, LocalNewsModule, "Local News", 60, RefreshClass.FIVE_MINUTES),
        Descriptor(0x0007, SportsHeadlinesModule, "Sports", 70, RefreshClass.FIVE_MINUTES),
        Descriptor(0x0008, FootballModule, "NFL Scores", 80, RefreshClass.PER_CYCLE),
        Descriptor(0x0009, ApTop25Module, "College Scores", 90, RefreshClass.PER_CYCLE),
        Descriptor(0x000a, FantasyLeadersModule, "Fantasy Leaders", 100, RefreshClass.PER_CYCLE),
        Descriptor(0x000b, EmailModule, "Email", 110, RefreshClass.PER_CYCLE),
        Descriptor(0x000c, TasksModule, "Tasks", 120, RefreshClass.STATIC),
        Descriptor(0x000d, GroceryModule, "Groceries", 130, RefreshClass.STATIC),
        Descriptor(0x000e, RecipeModule, "Recipe", 140, RefreshClass.STATIC),
        Descriptor(0x000f, StocksModule, "Stocks", 150, RefreshClass.PER_CYCLE),
        Descriptor(0x0010, MarketsModule, "Markets", 160, RefreshClass.PER_CYCLE),
        Descriptor(0x0011, TravelModule, "Travel", 170, RefreshClass.PER_CYCLE),
        Descriptor(0x0012, PackageTrackingModule, "Packages", 180, RefreshClass.PER_CYCLE)
    )

    private val byModule = catalog.associateBy { it.module.displayName }

    fun descriptorFor(module: Module): Descriptor =
        requireNotNull(byModule[module.displayName]) { "No collection identity for ${module.displayName}" }

    fun planEnabled(context: Context): List<Plan> = SidecarPlaylist.enabledModules(context)
        .map(::descriptorFor)
        .sortedBy { it.order }
        .map { descriptor ->
            val content = SidecarPlaylist.renderContent(context, descriptor.module)
            val pages = descriptor.module.pageCount(context, 528, 792, content)
            plan(descriptor, pages)
        }

    fun plan(descriptor: Descriptor, pageCount: Int, maxTransactionPages: Int = MAX_TRANSACTION_PAGES): Plan {
        require(pageCount in 1..MAX_COLLECTION_PAGES) {
            "${descriptor.name} page count $pageCount is outside 1..$MAX_COLLECTION_PAGES"
        }
        require(maxTransactionPages in 1..MAX_TRANSACTION_PAGES) {
            "Transaction size must be in 1..$MAX_TRANSACTION_PAGES"
        }
        val transactions = (0 until pageCount step maxTransactionPages).map { start ->
            PageTransaction(descriptor.id, start, minOf(maxTransactionPages, pageCount - start))
        }
        return Plan(descriptor, pageCount, transactions)
    }
}
