package com.dbykstore.reply

import android.content.Context
import android.content.SharedPreferences

/** All settings live in private app storage on this phone only. */
class Settings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("dbyk", Context.MODE_PRIVATE)

    fun get(key: String): String = prefs.getString(key, null) ?: DEFAULTS[key] ?: ""
    fun set(key: String, value: String) = prefs.edit().putString(key, value).apply()

    val anthropicKey get() = get("anthropicKey").trim()
    val model get() = get("model").trim()
    val shopDomain get() = get("shopDomain").trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore("/")
    val shopifyClientId get() = get("shopifyClientId").trim()
    val shopifyClientSecret get() = get("shopifyClientSecret").trim()
    val shopifyApiVersion get() = get("shopifyApiVersion").trim()
    val siteUrl get() = get("siteUrl").trim().trimEnd('/')
    val zohoOrgId get() = get("zohoOrgId").trim()
    val zohoClientId get() = get("zohoClientId").trim()
    val zohoClientSecret get() = get("zohoClientSecret").trim()
    val zohoRefreshToken get() = get("zohoRefreshToken").trim()
    val businessBrief get() = get("businessBrief")
    val styleNotes get() = get("styleNotes")

    val shopifyConfigured get() = shopDomain.isNotEmpty() && shopifyClientId.isNotEmpty() && shopifyClientSecret.isNotEmpty()
    val zohoConfigured get() = zohoOrgId.isNotEmpty() && zohoClientId.isNotEmpty() && zohoClientSecret.isNotEmpty() && zohoRefreshToken.isNotEmpty()

    companion object {
        val FIELDS = listOf(
            "anthropicKey", "model",
            "shopDomain", "shopifyClientId", "shopifyClientSecret", "shopifyApiVersion", "siteUrl",
            "zohoOrgId", "zohoClientId", "zohoClientSecret", "zohoRefreshToken",
            "businessBrief", "styleNotes"
        )

        val DEFAULTS = mapOf(
            "model" to "claude-sonnet-5-5",
            "shopDomain" to "d-byk-store-2.myshopify.com",
            "shopifyApiVersion" to "2026-07",
            "siteUrl" to "https://dbykstore.com",
            "zohoOrgId" to "651227715",
            "businessBrief" to """D Byk Store — cycling store in Pune.
Website: https://dbykstore.com · Email: info@dbykstore.com · Main WhatsApp: +91 70204 25618
Services page (share for booking): https://www.dbykstore.com/pages/services

Stores (all open 10:30 am–8:00 pm, closed Monday):
- Viman Nagar (flagship) — 1st Floor, Trade Net Building, Viman Nagar Junction, above Maruti Suzuki Showroom, Pune 411014. Phone +91 70204 25618, info@dbykstore.com
- Bavdhan — Office No. 4, First Floor, Brahma Vantage Tower, Ram Nagar, Bavdhan, Pune 411021. Phone +91 97660 95618, bavdhansc@dbykstore.com
- Wakad — Shop No. 115, W Biz, next to Vision One Mall, Bhumkar Chowk, Wakad, Pune 411057. Phone +91 84840 25618, wakad@dbykstore.com

Brands: Authorized Trek dealer (exclusive in Pune) and authorized Wahoo dealer. Also Garmin, Coros, Shimano, SRAM, Shokz, Steadfast and Unived.

Policies:
- No open-box returns.
- No change-of-mind / voluntary returns.
- Discounted / sale purchases are final.

SERVICING (torque-accurate servicing for road, MTB, gravel and carbon bikes)
Prices in INR, effective 1 Jan 2026. Prices EXCLUDE parts.

Package prices (Prime / Pro / Pro Annual):
- Alloy flat bar: ₹799 / ₹1,499 / ₹2,199
- Alloy drop bar or TT: ₹799 / ₹1,599 / ₹2,399
- Carbon flat bar: ₹899 / ₹1,699 / ₹2,399
- Carbon drop bar: ₹899 / ₹1,799 / ₹2,499
- Carbon TT: ₹999 / ₹1,999 / ₹2,999
- Fast Lane priority service add-on: +₹299 (Prime and Pro), +₹399 (Pro Annual)

Prime — full basic service for reliable daily riding: wash & lube, torque check on all bolts, brake alignment & adjustment, gear indexing & drivetrain tuning, headset adjustment, bottom bracket inspection. Carbon bikes also get UV frame inspection for hidden cracks and carbon-safe torque validation.

Pro — deep clean + mechanical overhaul: everything in Prime, plus advanced wash with drivetrain removal, complete drivetrain deep clean, wheel truing (lateral + vertical), hub overhaul, headset overhaul. Carbon bikes also get carbon-safe detailed cleaning and inspection of bars, seatpost and stem.

Pro Annual — full bike rejuvenation, recommended once a year: everything in Pro, plus complete bike spa (deep clean on a fully disassembled bike), advanced wheel truing, Muc-Off polish & protect, safety check & race tune, photo documentation of the overhaul. Carbon bikes also get professional carbon polish and a race-ready carbon safety check.

Standalone: Quick-Fix (single small adjustment) ₹199 · Wash & Lube ₹499.
ESP (annual maintenance contract) ₹4,999 — includes, within 1 year: 1 Prime service, 1 Pro service, 1 Pro Annual service and 1 bike wash. Parts extra.
Also offered (no listed price — say we'll share a quote): suspension service, custom builds.

Guarantee: if the same service issue comes back within 30 days, the labour is free.

Booking: customer books on the services page (choose store, name, number, email, bike needs) or simply tells us here. We confirm the slot on WhatsApp or by phone, usually the same day. They drop the bike at their chosen store. We inspect it first, and anything beyond the package is flagged and quoted before we do it. Service duration: [add — typical turnaround].

Other services and offers: [add — bike fit, test rides, EMI options, shipping across India, etc.]""",
            "styleNotes" to """- Warm, helpful and to the point — like a knowledgeable store person, not a bot.
- 1–4 short sentences for simple questions; a short list only when comparing options.
- Greet by first name when known. At most one emoji, and only if the customer uses them.
- End with a clear next step (visit the store, test ride, product link, or "we'll confirm and get back")."""
        )
    }
}
