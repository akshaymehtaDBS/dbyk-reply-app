package com.dbykstore.reply

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ChatMessage(val outgoing: Boolean, val text: String)
data class Lookup(val tool: String, val input: String, val summary: String)
data class Suggestion(val reply: String, val note: String, val lookups: List<Lookup>)

/** Same logic as the Chrome extension: Claude + Shopify catalogue + Zoho stock. Call from a background thread. */
class Engine(private val s: Settings) {

    // ---------------- HTTP ----------------

    private class Http(val code: Int, val body: String) {
        fun json(): JSONObject? = try { JSONObject(body) } catch (e: Exception) { null }
    }

    private fun http(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null): Http {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20000
        conn.readTimeout = 90000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        return Http(code, text)
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun form(vararg pairs: Pair<String, String>) = pairs.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }

    // ---------------- Shopify ----------------

    private fun shopifyToken(force: Boolean = false): String {
        val p = s.prefs
        val cached = p.getString("shopifyTokenCache", null)
        val exp = p.getLong("shopifyTokenExp", 0)
        if (!force && cached != null && exp > System.currentTimeMillis() + 5 * 60_000 &&
            p.getString("shopifyTokenFor", "") == s.shopDomain + s.shopifyClientId) return cached

        val r = http(
            "POST", "https://${s.shopDomain}/admin/oauth/access_token",
            mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
            form("grant_type" to "client_credentials", "client_id" to s.shopifyClientId, "client_secret" to s.shopifyClientSecret)
        )
        val j = r.json()
        val token = j?.optString("access_token").orEmpty()
        if (r.code !in 200..299 || token.isEmpty()) {
            throw Exception("Shopify token request failed (${r.code}): ${j?.optString("error_description") ?: r.body.take(200)}")
        }
        p.edit()
            .putString("shopifyTokenCache", token)
            .putLong("shopifyTokenExp", System.currentTimeMillis() + (j!!.optLong("expires_in", 86399)) * 1000)
            .putString("shopifyTokenFor", s.shopDomain + s.shopifyClientId)
            .apply()
        return token
    }

    private fun gql(query: String, variables: JSONObject = JSONObject(), retried: Boolean = false): JSONObject {
        val token = shopifyToken(retried)
        val r = http(
            "POST", "https://${s.shopDomain}/admin/api/${s.shopifyApiVersion}/graphql.json",
            mapOf("Content-Type" to "application/json", "X-Shopify-Access-Token" to token),
            JSONObject().put("query", query).put("variables", variables).toString()
        )
        if (r.code == 401 && !retried) return gql(query, variables, true)
        val j = r.json() ?: throw Exception("Shopify ${r.code}: ${r.body.take(200)}")
        if (r.code !in 200..299) throw Exception("Shopify ${r.code}: ${j.opt("errors")?.toString()?.take(300)}")
        val data = j.optJSONObject("data")
        if (data == null) {
            val errs = j.optJSONArray("errors")
            throw Exception("Shopify: ${errs?.toString()?.take(300) ?: "no data"}")
        }
        return data
    }

    private fun nodes(o: JSONObject?, key: String): List<JSONObject> {
        val arr = o?.optJSONObject(key)?.optJSONArray("nodes") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private val PRODUCT_Q = """query(${'$'}q: String!) {
      products(first: 10, query: ${'$'}q, sortKey: RELEVANCE) {
        nodes {
          title handle vendor productType totalInventory
          variants(first: 12) { nodes { title sku price compareAtPrice inventoryQuantity inventoryItem { tracked } } }
        }
      }
    }"""

    private fun searchProducts(input: JSONObject): JSONObject {
        val q = "${input.optString("query").trim()} status:active"
        val d = gql(PRODUCT_Q, JSONObject().put("q", q))
        val products = JSONArray()
        for (p in nodes(d, "products")) {
            val variants = JSONArray()
            for (v in nodes(p, "variants")) {
                val price = v.optString("price")
                val mrp = v.optString("compareAtPrice", "")
                variants.put(JSONObject().apply {
                    if (v.optString("title") != "Default Title") put("variant", v.optString("title"))
                    if (v.optString("sku").isNotEmpty()) put("sku", v.optString("sku"))
                    put("price_inr", price)
                    if (mrp.isNotEmpty() && mrp != "null" && (mrp.toDoubleOrNull() ?: 0.0) > (price.toDoubleOrNull() ?: 0.0)) put("mrp_inr", mrp)
                    if (!s.zohoConfigured) put("shopify_stock", v.opt("inventoryQuantity"))
                })
            }
            products.put(JSONObject()
                .put("title", p.optString("title"))
                .put("vendor", p.optString("vendor"))
                .put("url", "${s.siteUrl}/products/${p.optString("handle")}")
                .put("variants", variants))
        }
        return JSONObject().put("count", products.length()).put("products", products)
    }

    private val BIKE_CATEGORIES = linkedMapOf(
        "road" to "tag:'Road Bikes'",
        "hybrid" to "(tag:Hybrids OR tag:'Urban/Hybrid Bikes')",
        "mountain" to "tag:'Mountain Bikes'",
        "gravel" to "tag:'Gravel & Touring Bikes'",
        "tt_triathlon" to "tag:'TT / Tri Bikes'",
        "kids" to "tag:'Kids/Youth bikes'",
        "ebike" to "(tag:'E-Bike' OR tag:'Electric Bike')",
        "frameset" to "(tag:'Bikes>>Frameset' OR product_type:Frameset)"
    )

    private val BROWSE_Q = """query(${'$'}q: String!) {
      products(first: 60, query: ${'$'}q) {
        nodes {
          title handle vendor totalInventory
          priceRangeV2 { minVariantPrice { amount } }
          variants(first: 10) { nodes { title price compareAtPrice inventoryQuantity } }
        }
      }
    }"""

    private fun browseBikes(input: JSONObject): JSONObject {
        val category = input.optString("category")
        val cat = BIKE_CATEGORIES[category] ?: throw Exception("Unknown category \"$category\". Use one of: ${BIKE_CATEGORIES.keys.joinToString()}")
        val parts = mutableListOf("status:active", cat)
        val minP = input.optDouble("min_price", 0.0)
        val maxP = input.optDouble("max_price", 0.0)
        val brand = input.optString("brand", "").replace("'", "")
        // With Zoho connected, Shopify stock isn't trusted, so don't filter on it.
        val inStockOnly = input.optBoolean("in_stock_only", !s.zohoConfigured)
        if (minP > 0) parts += "price:>=${minP.toLong()}"
        if (maxP > 0) parts += "price:<=${maxP.toLong()}"
        if (brand.isNotEmpty()) parts += "vendor:'$brand'"
        if (inStockOnly) parts += "inventory_total:>0"

        val d = gql(BROWSE_Q, JSONObject().put("q", parts.joinToString(" ")))
        val bikes = nodes(d, "products")
            .filter { category == "frameset" || !Regex("frame\\s*set", RegexOption.IGNORE_CASE).containsMatchIn(it.optString("title")) }
            .map { p ->
                val vs = nodes(p, "variants")
                val prices = vs.mapNotNull { it.optString("price").toDoubleOrNull() }.filter { it > 0 }
                val price = prices.minOrNull() ?: (p.optJSONObject("priceRangeV2")?.optJSONObject("minVariantPrice")?.optString("amount")?.toDoubleOrNull() ?: 0.0)
                val mrp = vs.mapNotNull { it.optString("compareAtPrice").toDoubleOrNull() }.maxOrNull() ?: 0.0
                JSONObject().apply {
                    put("title", p.optString("title"))
                    put("brand", p.optString("vendor"))
                    put("price_inr", price.toLong())
                    if (mrp > price) put("mrp_inr", mrp.toLong())
                    if (!s.zohoConfigured) put("shopify_stock", p.opt("totalInventory"))
                    put("url", "${s.siteUrl}/products/${p.optString("handle")}")
                }
            }
            .sortedBy { it.optLong("price_inr") }

        val brands = bikes.map { it.optString("brand") }.distinct()
        val top = bikes.take(30)
        return JSONObject().apply {
            put("category", category)
            put("total_found", bikes.size)
            put("brands_found", JSONArray(brands))
            if (top.isNotEmpty()) put("price_range_found", "₹${top.first().optLong("price_inr")} – ₹${top.last().optLong("price_inr")}")
            put("bikes_cheapest_first", JSONArray(top))
            if (s.zohoConfigured) put("note_stock", "Listed on Shopify only — check zoho_stock for real availability before saying it's in stock.")
            if (bikes.isEmpty()) put("note", "Nothing matched. Try widening the budget or removing the brand.")
        }
    }

    private val ORDER_FIELDS = """
      name createdAt cancelledAt displayFinancialStatus displayFulfillmentStatus
      totalPriceSet { shopMoney { amount currencyCode } }
      customer { displayName }
      lineItems(first: 10) { nodes { title quantity } }
      fulfillments(first: 5) { displayStatus createdAt trackingInfo(first: 3) { company number url } }
    """

    private fun formatOrder(o: JSONObject): JSONObject {
        val items = JSONArray(nodes(o, "lineItems").map { "${it.optInt("quantity")} × ${it.optString("title")}" })
        val fulfils = o.optJSONArray("fulfillments") ?: JSONArray()
        val ships = JSONArray()
        for (i in 0 until fulfils.length()) {
            val f = fulfils.getJSONObject(i)
            ships.put(JSONObject().put("status", f.optString("displayStatus")).put("date", f.optString("createdAt").take(10))
                .put("tracking", f.optJSONArray("trackingInfo") ?: JSONArray()))
        }
        val money = o.optJSONObject("totalPriceSet")?.optJSONObject("shopMoney")
        return JSONObject()
            .put("order", o.optString("name"))
            .put("placed", o.optString("createdAt").take(10))
            .put("cancelled", if (o.isNull("cancelledAt")) JSONObject.NULL else o.optString("cancelledAt").take(10))
            .put("payment", o.optString("displayFinancialStatus"))
            .put("fulfillment", o.optString("displayFulfillmentStatus"))
            .put("total", if (money != null) "${money.optString("amount")} ${money.optString("currencyCode")}" else JSONObject.NULL)
            .put("customer", o.optJSONObject("customer")?.optString("displayName") ?: JSONObject.NULL)
            .put("items", items)
            .put("shipments", ships)
    }

    private fun getOrder(input: JSONObject): JSONObject {
        val n = input.optString("order_number").replace(Regex("[^0-9A-Za-z-]"), "")
        if (n.isEmpty()) throw Exception("No order number given")
        val d = gql(
            "query(\$q: String!) { orders(first: 3, query: \$q, sortKey: CREATED_AT, reverse: true) { nodes { $ORDER_FIELDS } } }",
            JSONObject().put("q", "name:#$n OR name:$n")
        )
        val digits = n.filter { it.isDigit() }
        val orders = nodes(d, "orders").filter { it.optString("name").filter { c -> c.isDigit() } == digits }.map { formatOrder(it) }
        return JSONObject().put("orders", JSONArray(orders)).apply {
            if (orders.isEmpty()) put("message", "No order found with that number.")
        }
    }

    private fun findCustomer(input: JSONObject): JSONObject {
        val q = input.optString("query").trim()
        val digits = q.filter { it.isDigit() }
        val search = when {
            digits.length >= 10 -> "phone:" + (if (digits.length == 10) "+91$digits" else "+$digits")
            q.contains("@") -> "email:$q"
            else -> q
        }
        val d = gql(
            """query(${'$'}q: String!) { customers(first: 3, query: ${'$'}q) { nodes {
                displayName numberOfOrders amountSpent { amount currencyCode }
                orders(first: 5, sortKey: CREATED_AT, reverse: true) { nodes { $ORDER_FIELDS } } } } }""",
            JSONObject().put("q", search)
        )
        val customers = nodes(d, "customers").map { c ->
            JSONObject()
                .put("name", c.optString("displayName"))
                .put("total_orders", c.opt("numberOfOrders"))
                .put("recent_orders", JSONArray(nodes(c, "orders").map { formatOrder(it) }))
        }
        return JSONObject().put("customers", JSONArray(customers)).apply {
            if (customers.isEmpty()) put("message", "No matching customer found.")
        }
    }

    // ---------------- Zoho Books ----------------

    private data class Dc(val id: String, val accounts: String, val api: String)
    private val DCS = listOf(
        Dc("in", "https://accounts.zoho.in", "https://www.zohoapis.in"),
        Dc("com", "https://accounts.zoho.com", "https://www.zohoapis.com")
    )

    private fun orderedDcs(): List<Dc> {
        val pref = s.prefs.getString("zohoDc", null)
        return DCS.sortedBy { if (it.id == pref) 0 else 1 }
    }

    /** Returns access token and Books API base. */
    private fun zohoToken(force: Boolean = false): Pair<String, String> {
        val p = s.prefs
        val tok = p.getString("zohoTokenCache", null)
        val api = p.getString("zohoApiBase", null)
        if (!force && tok != null && api != null && p.getLong("zohoTokenExp", 0) > System.currentTimeMillis() + 2 * 60_000 &&
            p.getString("zohoTokenFor", "") == s.zohoRefreshToken.takeLast(8)) return tok to api

        val errors = mutableListOf<String>()
        for (dc in orderedDcs()) {
            val r = http("POST", "${dc.accounts}/oauth/v2/token?" + form(
                "refresh_token" to s.zohoRefreshToken, "client_id" to s.zohoClientId,
                "client_secret" to s.zohoClientSecret, "grant_type" to "refresh_token"
            ))
            val j = r.json()
            val access = j?.optString("access_token").orEmpty()
            if (r.code in 200..299 && access.isNotEmpty()) {
                val base = (j!!.optString("api_domain").ifEmpty { dc.api }).trimEnd('/') + "/books/v3"
                p.edit()
                    .putString("zohoTokenCache", access)
                    .putString("zohoApiBase", base)
                    .putLong("zohoTokenExp", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000)
                    .putString("zohoTokenFor", s.zohoRefreshToken.takeLast(8))
                    .putString("zohoDc", dc.id)
                    .apply()
                return access to base
            }
            errors += "${dc.id}: ${j?.optString("error") ?: r.code}"
        }
        throw Exception("Zoho login failed (${errors.joinToString()}). Check Client ID, secret and refresh token.")
    }

    private fun zohoGet(path: String, params: Map<String, String> = emptyMap(), retried: Boolean = false): JSONObject {
        val (token, api) = zohoToken(retried)
        val qs = form(*(mapOf("organization_id" to s.zohoOrgId) + params).map { it.key to it.value }.toTypedArray())
        val r = http("GET", "$api$path?$qs", mapOf("Authorization" to "Zoho-oauthtoken $token"))
        if (r.code == 401 && !retried) return zohoGet(path, params, true)
        val j = r.json()
        if (r.code !in 200..299 || j == null || j.optInt("code", -1) != 0) {
            throw Exception("Zoho ${r.code}: ${j?.optString("message") ?: "request failed"}")
        }
        return j
    }

    private fun storeFor(location: String): String {
        val n = location.lowercase()
        return when {
            "viman" in n -> "Viman Nagar"
            "bavdhan" in n -> "Bavdhan"
            "wakad" in n -> "Wakad"
            else -> location.ifEmpty { "Other" }
        }
    }

    private fun zohoStock(input: JSONObject): JSONObject {
        val arr = input.optJSONArray("queries")
        val queries = if (arr != null) (0 until arr.length()).map { arr.optString(it).trim() } else listOf(input.optString("queries").trim())
        val list = queries.filter { it.isNotEmpty() }.take(5)
        if (list.isEmpty()) throw Exception("Give at least one search term.")
        var detailBudget = 12
        val results = JSONArray()
        for (q in list) {
            val data = zohoGet("/items", mapOf("search_text" to q, "filter_by" to "Status.Active", "per_page" to "40"))
            val items = data.optJSONArray("items") ?: JSONArray()
            val inStock = JSONArray()
            val out = mutableListOf<String>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.optString("item_type") != "inventory" && !item.optBoolean("track_inventory")) continue
                val total = item.optDouble("available_for_sale", item.optDouble("stock_on_hand", 0.0))
                if (total <= 0) { out += item.optString("name"); continue }
                val row = JSONObject()
                    .put("name", item.optString("name"))
                    .put("sku", item.optString("sku"))
                    .put("zoho_rate_inr", item.opt("rate"))
                    .put("total_available", total.toInt())
                if (detailBudget > 0) {
                    detailBudget--
                    try {
                        val d = zohoGet("/items/${item.optString("item_id")}")
                        val locs = d.optJSONObject("item")?.optJSONArray("locations") ?: JSONArray()
                        val byStore = linkedMapOf<String, Int>()
                        for (k in 0 until locs.length()) {
                            val l = locs.getJSONObject(k)
                            val qty = l.optDouble("location_available_for_sale_stock", l.optDouble("location_available_stock", 0.0)).toInt()
                            if (qty > 0) { val st = storeFor(l.optString("location_name")); byStore[st] = (byStore[st] ?: 0) + qty }
                        }
                        row.put("by_store", JSONObject(byStore as Map<*, *>))
                    } catch (e: Exception) {
                        row.put("by_store_error", e.message)
                    }
                } else {
                    row.put("by_store", "not checked (too many matches — search more specifically)")
                }
                inStock.put(row)
            }
            results.put(JSONObject()
                .put("query", q)
                .put("in_stock", inStock)
                .put("out_of_stock", JSONArray(out.take(20)))
                .apply {
                    if (data.optJSONObject("page_context")?.optBoolean("has_more_page") == true)
                        put("more_matches", "More items match — use a more specific term (add size or colour).")
                })
        }
        return JSONObject().put("source", "Zoho Books (actual shop stock)").put("results", results)
    }

    // ---------------- Tools ----------------

    private val TOOLS_JSON = """[
      {"name":"browse_bikes","description":"List complete bikes in one category across ALL brands, cheapest first, with price, MRP and link. Use this — not search_products — whenever the customer asks for a type of bike (road, hybrid, MTB, gravel…), gives a budget, or asks which bike to buy. Categories come from the store's own tags, so \"road\" returns only drop-bar road bikes and never hybrids. Prices are INR numbers (e.g. 100000 for ₹1 lakh).",
       "input_schema":{"type":"object","properties":{
         "category":{"type":"string","enum":["road","hybrid","mountain","gravel","tt_triathlon","kids","ebike","frameset"]},
         "min_price":{"type":"number","description":"Optional, INR"},
         "max_price":{"type":"number","description":"Optional, INR. Use the customer budget, allowing ~10% stretch only if nothing fits."},
         "brand":{"type":"string","description":"Optional. Only if the customer asked for a specific brand."}},
         "required":["category"]}},
      {"name":"search_products","description":"Search the catalogue by keyword for a SPECIFIC model or accessory (e.g. \"domane al 2\", \"wahoo kickr\", \"edge 1050\", \"helmet\"). Returns per-variant price, MRP and SKU. For \"show me road/hybrid bikes\" use browse_bikes instead.",
       "input_schema":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}},
      {"name":"zoho_stock","description":"REAL stock per store (Viman Nagar, Bavdhan, Wakad) from Zoho Books — the source of truth for availability. Zoho has one item per size/colour, named like \"TREK DOMANE AL 2 52 RD-BK\". Search with short model words without the brand (e.g. \"domane al 2\", \"scultura rim 100\") or a SKU. Pass several models at once. Returns in-stock items with quantity by store, plus out-of-stock sizes.",
       "input_schema":{"type":"object","properties":{"queries":{"type":"array","items":{"type":"string"},"description":"Up to 5 search terms"}},"required":["queries"]}},
      {"name":"get_order","description":"Look up a Shopify order by its order number (e.g. 1234 or #1234). Returns payment and fulfillment status, items and tracking.",
       "input_schema":{"type":"object","properties":{"order_number":{"type":"string"}},"required":["order_number"]}},
      {"name":"find_customer","description":"Find a customer by phone number, email or name and return their recent orders. Use when someone asks about \"my order\" without an order number. The chat title is often their saved name or phone number.",
       "input_schema":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}
    ]"""

    private fun tools(): JSONArray {
        val all = JSONArray(TOOLS_JSON)
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val t = all.getJSONObject(i)
            val ok = if (t.getString("name") == "zoho_stock") s.zohoConfigured else s.shopifyConfigured
            if (ok) out.put(t)
        }
        return out
    }

    private fun runTool(name: String, input: JSONObject): JSONObject = when (name) {
        "browse_bikes" -> browseBikes(input)
        "search_products" -> searchProducts(input)
        "zoho_stock" -> zohoStock(input)
        "get_order" -> getOrder(input)
        "find_customer" -> findCustomer(input)
        else -> throw Exception("Unknown tool $name")
    }

    private fun summarize(name: String, o: JSONObject): String = try {
        when (name) {
            "browse_bikes" -> if (o.optInt("total_found") > 0) "${o.optInt("total_found")} bikes (${o.optJSONArray("brands_found")?.join(", ")?.replace("\"", "")}), ${o.optString("price_range_found")}" else "no matches"
            "search_products" -> {
                val p = o.optJSONArray("products") ?: JSONArray()
                if (p.length() == 0) "no matches" else (0 until minOf(4, p.length())).joinToString { p.getJSONObject(it).optString("title") }
            }
            "zoho_stock" -> {
                val r = o.optJSONArray("results") ?: JSONArray()
                (0 until r.length()).joinToString(" | ") { i ->
                    val res = r.getJSONObject(i)
                    val inS = res.optJSONArray("in_stock") ?: JSONArray()
                    val items = (0 until inS.length()).joinToString { k ->
                        val it = inS.getJSONObject(k)
                        val bs = it.optJSONObject("by_store")
                        "${it.optString("name")} " + (bs?.keys()?.asSequence()?.joinToString("/") { key -> "$key ${bs.optInt(key)}" } ?: "(${it.optInt("total_available")})")
                    }
                    "${res.optString("query")}: ${items.ifEmpty { "none in stock" }}"
                }
            }
            "get_order" -> {
                val a = o.optJSONArray("orders") ?: JSONArray()
                if (a.length() == 0) "not found" else (0 until a.length()).joinToString { "${a.getJSONObject(it).optString("order")}: ${a.getJSONObject(it).optString("fulfillment")}" }
            }
            "find_customer" -> {
                val a = o.optJSONArray("customers") ?: JSONArray()
                if (a.length() == 0) "not found" else (0 until a.length()).joinToString { "${a.getJSONObject(it).optString("name")} (${a.getJSONObject(it).opt("total_orders")} orders)" }
            }
            else -> "done"
        }
    } catch (e: Exception) { "done" }

    // ---------------- Claude ----------------

    private fun callClaude(body: JSONObject): JSONObject {
        if (s.anthropicKey.isEmpty()) throw Exception("Add your Anthropic API key in the D Byk Reply app.")
        val r = http(
            "POST", "https://api.anthropic.com/v1/messages",
            mapOf("content-type" to "application/json", "x-api-key" to s.anthropicKey, "anthropic-version" to "2023-06-01"),
            body.toString()
        )
        val j = r.json()
        if (r.code !in 200..299) throw Exception("Claude API ${r.code}: ${j?.optJSONObject("error")?.optString("message") ?: r.body.take(200)}")
        return j ?: throw Exception("Claude API: empty response")
    }

    private fun systemPrompt(): String {
        val stockRules = if (s.zohoConfigured) """
- STOCK = ZOHO. Shopify is only the catalogue (what we sell, price, link) — its stock and store locations are NOT reliable. Before saying anything is available, in which size, or at which store, check zoho_stock. Typical flow: browse_bikes / search_products to shortlist → zoho_stock for those models (several at once) → reply.
- Only say a store has it if zoho_stock shows it there. Warehouse stock is already merged into its store. If Zoho shows 0 everywhere, say it's currently out of stock / can be ordered. If a model can't be found in Zoho, say we'll confirm availability and add a note for Akshay.
- Quote the Shopify price; use the Zoho rate only if the item isn't on Shopify.""" else """
- Shopify stock can lag the shop floor, so for a store visit suggest they confirm before coming."""

        return """You draft WhatsApp replies for D Byk Store. Akshay or a staff member reviews every draft and sends it manually, so write exactly the message that should be sent.

## Business information
${s.businessBrief}

## Reply style
${s.styleNotes}

## Rules
- Reply to the customer's latest unanswered message(s); use earlier messages only for context. If the last message is already from us and nothing needs answering, write a short natural follow-up only if it makes sense, and say so in the note.
- Match the customer's language and script (English, Hindi, Marathi, or Hinglish written in English letters).
- Before answering anything about stock, availability, sizes, prices or an order, look it up with the tools. Never invent stock, prices, specs, delivery dates, discounts or offers.
- If a lookup finds nothing or the data is unclear, say you'll check and get back shortly — don't guess.
- Write prices in rupees with Indian digit grouping, e.g. ₹1,24,500. If an MRP higher than the price is returned, you may mention the offer price.$stockRules
- Share the product link returned by the tool (${s.siteUrl}/products/...) when it helps.
- Ignore bracketed placeholders like [add] in the business information — never state information that isn't actually given.
- Don't promise discounts, EMI, delivery timelines or exceptions to policy unless the business information or Akshay's instruction allows it.
- WhatsApp formatting only: *bold* sparingly, no markdown headings or tables. Keep it short.
- The chat was read from the phone screen, so only the visible messages are included; some may be cut off or missing media.

## Recommending bikes — read the room
- Give the customer what THEY asked for. "Road bike" means drop-bar road bikes only (browse_bikes category "road"). Never swap in a hybrid or flat-bar bike unless they ask, or what they describe clearly fits one better — and then offer it as a clearly labelled alternative AFTER the road options, saying why.
- Be brand-neutral. Being a Trek dealer is a fact about the store, not a reason to push Trek. Choose on fit to their budget, riding and experience, and value for money. If Merida, Polygon, Bonzai, Cannondale, Giant etc. suit them better or cost less, recommend those. Mix brands when several fit.
- Budget is king. If they gave a budget, stay within it (go up to ~10% over only if nothing fits, and say so). If they sound price-conscious ("budget", "best value", "sasta", "kam price", "starting", "first bike"), lead with the cheapest suitable options.
- No budget given? Don't assume premium. Show 2–3 options at different price points (entry / mid / higher) and ask their budget in one short line.
- Match their level. Beginners, fitness or commuting → entry-level, comfortable, simple, no jargon. Racers, upgraders, people who name groupsets or frame materials → you can go higher-end and more technical.
- If key details are missing (budget, type of riding, height for sizing), ask 1–2 short questions instead of guessing — you can still give a couple of options alongside.
- Maximum 3 bikes per message: name, price, one line on why it suits them, link. Prefer bikes in stock in their size.
- Always use browse_bikes for category/budget questions so every brand and price point is considered.

## Output format
<reply>the exact message to send</reply>
<note>optional one-line private note for Akshay (e.g. something you couldn't verify); leave out if not needed</note>"""
    }

    fun suggest(chatTitle: String, messages: List<ChatMessage>, instruction: String, onProgress: (String) -> Unit = {}): Suggestion {
        if (messages.isEmpty()) throw Exception("No messages found in the open chat.")
        val transcript = messages.joinToString("\n") { (if (it.outgoing) "D Byk Store (us)" else "Customer") + ": " + it.text }
        val fmt = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }
        val parts = mutableListOf(
            "WhatsApp chat with: $chatTitle",
            "Now: ${fmt.format(Date())}",
            "",
            "Recent messages visible on screen (oldest first):",
            transcript,
            ""
        )
        if (instruction.isNotBlank()) parts += "Akshay's instruction for this reply: ${instruction.trim()}"
        if (!s.shopifyConfigured && !s.zohoConfigured) parts += "(Shopify and Zoho are not connected, so you cannot check stock, prices or orders — say you will check if needed.)"
        parts += "Draft the next WhatsApp reply from D Byk Store."

        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", parts.joinToString("\n")))
        val toolList = tools()
        val lookups = mutableListOf<Lookup>()
        val maxRounds = 8
        var final: JSONObject? = null

        for (round in 0 until maxRounds) {
            val body = JSONObject()
                .put("model", s.model)
                .put("max_tokens", 1500)
                .put("system", systemPrompt())
                .put("messages", msgs)
            if (toolList.length() > 0) {
                body.put("tools", toolList)
                if (round == maxRounds - 1) body.put("tool_choice", JSONObject().put("type", "none"))
            }
            val res = callClaude(body)
            val content = res.getJSONArray("content")
            msgs.put(JSONObject().put("role", "assistant").put("content", content))
            if (res.optString("stop_reason") != "tool_use") { final = res; break }

            val results = JSONArray()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") != "tool_use") continue
                val name = block.getString("name")
                val input = block.optJSONObject("input") ?: JSONObject()
                onProgress("Checking ${when (name) { "zoho_stock" -> "Zoho stock"; "browse_bikes" -> "bikes"; "get_order" -> "order"; "find_customer" -> "customer"; else -> "products" }}…")
                val result = JSONObject().put("type", "tool_result").put("tool_use_id", block.getString("id"))
                try {
                    val out = runTool(name, input)
                    lookups += Lookup(name, input.toString(), summarize(name, out))
                    result.put("content", out.toString().take(15000))
                } catch (e: Exception) {
                    lookups += Lookup(name, input.toString(), "error: ${e.message}")
                    result.put("content", JSONObject().put("error", e.message).toString()).put("is_error", true)
                }
                results.put(result)
            }
            msgs.put(JSONObject().put("role", "user").put("content", results))
            onProgress("Writing reply…")
        }
        val fin = final ?: throw Exception("Claude did not finish — try again.")
        val c = fin.getJSONArray("content")
        val text = (0 until c.length()).map { c.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }.trim()
        val reply = Regex("<reply>([\\s\\S]*?)(</reply>|$)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
            ?: text.replace(Regex("<note>[\\s\\S]*?</note>", RegexOption.IGNORE_CASE), "").trim()
        val note = Regex("<note>([\\s\\S]*?)</note>", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim().orEmpty()
        return Suggestion(reply, note, lookups)
    }

    // ---------------- Settings tests ----------------

    fun testClaude(): String {
        val res = callClaude(JSONObject().put("model", s.model).put("max_tokens", 16)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with just: OK"))))
        val c = res.getJSONArray("content")
        return "Connected. ${s.model} replied \"${(0 until c.length()).joinToString("") { c.getJSONObject(it).optString("text") }.trim()}\"."
    }

    fun testShopify(): String {
        if (!s.shopifyConfigured) throw Exception("Fill in the Shopify domain, Client ID and secret first.")
        val d = gql("{ shop { name } }")
        return "Connected to ${d.optJSONObject("shop")?.optString("name")}."
    }

    fun testZoho(): String {
        if (!s.zohoConfigured) throw Exception("Fill in Organization ID, Client ID, secret and refresh token first.")
        val d = zohoGet("/locations")
        val locs = d.optJSONArray("locations") ?: JSONArray()
        return "Connected to Zoho. " + (0 until locs.length()).joinToString { val n = locs.getJSONObject(it).optString("location_name"); "$n → ${storeFor(n)}" }
    }

    /** One-time: Self Client grant code → refresh token (saved). */
    fun exchangeZohoCode(code: String): String {
        if (s.zohoClientId.isEmpty() || s.zohoClientSecret.isEmpty()) throw Exception("Save the Zoho Client ID and secret first.")
        val errors = mutableListOf<String>()
        for (dc in DCS) {
            val r = http("POST", "${dc.accounts}/oauth/v2/token?" + form(
                "code" to code.trim(), "client_id" to s.zohoClientId, "client_secret" to s.zohoClientSecret, "grant_type" to "authorization_code"
            ))
            val j = r.json()
            val rt = j?.optString("refresh_token").orEmpty()
            if (rt.isNotEmpty()) {
                s.set("zohoRefreshToken", rt)
                s.prefs.edit().putString("zohoDc", dc.id).remove("zohoTokenCache").apply()
                return rt
            }
            errors += "${dc.id}: ${j?.optString("error") ?: r.code}"
        }
        throw Exception("Zoho didn't return a refresh token (${errors.joinToString()}). Codes expire fast and work once — generate a new one.")
    }
}
