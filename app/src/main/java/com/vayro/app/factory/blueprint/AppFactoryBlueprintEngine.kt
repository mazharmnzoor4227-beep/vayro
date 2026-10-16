package com.vayro.app.factory.blueprint

import com.vayro.app.factory.model.ApiEndpointSpec
import com.vayro.app.factory.model.AppBlueprint
import com.vayro.app.factory.model.AppFramework
import com.vayro.app.factory.model.BuildConfigSpec
import com.vayro.app.factory.model.BuildMode
import com.vayro.app.factory.model.DataEntityField
import com.vayro.app.factory.model.DataEntitySpec
import com.vayro.app.factory.model.PlayStoreMetadataSpec
import com.vayro.app.factory.model.ScreenSpec
import java.util.UUID

/**
 * Parses user requirements or pre-built archetypes into comprehensive AppBlueprints.
 */
object AppFactoryBlueprintEngine {

    enum class Archetype(val title: String, val description: String) {
        ECOMMERCE("E-Commerce Marketplace", "Catalog, cart, checkout, payments, order tracking"),
        FOOD_DELIVERY("Food & Grocery Delivery", "Restaurant discovery, menus, active order tracking"),
        CHAT_SOCIAL("Chat & Social Messenger", "1:1 and group chats, media sharing, user feed"),
        RIDE_SHARING("Ride Booking & Mobility", "Pick-up / drop-off routing, driver assignment, receipts"),
        TASK_PRODUCTIVITY("Task & Project Manager", "Kanban boards, reminders, priorities, activity logs"),
        FITNESS_TRACKER("Fitness & Workout Tracker", "Exercise routines, sets/reps logging, analytics"),
        NOTES_KNOWLEDGE("Notes & Knowledge Vault", "Markdown notes, notebooks, tags, full-text search"),
        FINANCE_BUDGET("Personal Finance & Budget", "Income & expense tracking, accounts, financial analytics")
    }

    /**
     * Synthesizes an AppBlueprint from natural language or selected archetype.
     */
    fun createBlueprint(
        prompt: String,
        framework: AppFramework = AppFramework.ANDROID_COMPOSE,
        buildMode: BuildMode = BuildMode.ON_DEVICE,
        forcedArchetype: Archetype? = null
    ): AppBlueprint {
        val archetype = forcedArchetype ?: detectArchetype(prompt)
        val appName = extractAppName(prompt, archetype)
        val packageName = sanitizePackageName(appName)
        val id = UUID.randomUUID().toString()

        val (entities, endpoints, screens) = when (archetype) {
            Archetype.ECOMMERCE -> buildEcommerceSpec()
            Archetype.FOOD_DELIVERY -> buildFoodDeliverySpec()
            Archetype.CHAT_SOCIAL -> buildChatSocialSpec()
            Archetype.RIDE_SHARING -> buildRideSharingSpec()
            Archetype.TASK_PRODUCTIVITY -> buildTaskProductivitySpec()
            Archetype.FITNESS_TRACKER -> buildFitnessTrackerSpec()
            Archetype.NOTES_KNOWLEDGE -> buildNotesSpec()
            Archetype.FINANCE_BUDGET -> buildFinanceBudgetSpec()
        }

        val buildConfig = BuildConfigSpec(
            applicationId = packageName,
            appName = appName,
            versionCode = 1,
            versionName = "1.0.0",
            minSdk = 26,
            targetSdk = 35,
            compileSdk = 35
        )

        val playStoreMetadata = PlayStoreMetadataSpec(
            title = if (appName.length > 30) appName.take(30) else appName,
            shortDescription = "Fast, native $appName with modern architecture and offline support.".take(80),
            fullDescription = """
                Welcome to $appName!
                
                Experience lightning-fast performance, elegant design, and robust offline support.
                Key Features:
                ${screens.joinToString("\n") { "• ${it.title}: ${it.description}" }}
                
                Designed for high responsiveness, privacy, and seamless mobile productivity.
            """.trimIndent().take(4000),
            category = when (archetype) {
                Archetype.ECOMMERCE -> "Shopping"
                Archetype.FOOD_DELIVERY -> "Food & Drink"
                Archetype.CHAT_SOCIAL -> "Social"
                Archetype.RIDE_SHARING -> "Maps & Navigation"
                Archetype.TASK_PRODUCTIVITY -> "Productivity"
                Archetype.FITNESS_TRACKER -> "Health & Fitness"
                Archetype.NOTES_KNOWLEDGE -> "Productivity"
                Archetype.FINANCE_BUDGET -> "Finance"
            },
            contactEmail = "support@${appName.lowercase().replace(Regex("[^a-z0-9]"), "")}.app",
            privacyPolicyUrl = "https://${appName.lowercase().replace(Regex("[^a-z0-9]"), "")}.app/privacy",
            targetSdkCompliant = true,
            contentRating = "Everyone"
        )

        return AppBlueprint(
            id = id,
            name = appName,
            packageName = packageName,
            description = prompt.ifBlank { archetype.description },
            framework = framework,
            buildMode = buildMode,
            entities = entities,
            endpoints = endpoints,
            screens = screens,
            buildConfig = buildConfig,
            playStoreMetadata = playStoreMetadata
        )
    }

    /**
     * Inspects prompt keywords to infer matching archetype.
     */
    fun detectArchetype(prompt: String): Archetype {
        val lower = prompt.lowercase()
        return when {
            lower.contains("food") || lower.contains("restaurant") || lower.contains("delivery") || lower.contains("dish") || lower.contains("meal") -> Archetype.FOOD_DELIVERY
            lower.contains("shop") || lower.contains("store") || lower.contains("commerce") || lower.contains("cart") || lower.contains("buy") || lower.contains("product") -> Archetype.ECOMMERCE
            lower.contains("chat") || lower.contains("message") || lower.contains("social") || lower.contains("feed") || lower.contains("dm") || lower.contains("conversation") -> Archetype.CHAT_SOCIAL
            lower.contains("ride") || lower.contains("taxi") || lower.contains("cab") || lower.contains("driver") || lower.contains("trip") -> Archetype.RIDE_SHARING
            lower.contains("fit") || lower.contains("gym") || lower.contains("workout") || lower.contains("exercise") || lower.contains("health") -> Archetype.FITNESS_TRACKER
            lower.contains("note") || lower.contains("wiki") || lower.contains("memo") || lower.contains("journal") || lower.contains("docs") -> Archetype.NOTES_KNOWLEDGE
            lower.contains("finance") || lower.contains("budget") || lower.contains("expense") || lower.contains("money") || lower.contains("crypto") || lower.contains("wallet") -> Archetype.FINANCE_BUDGET
            lower.contains("task") || lower.contains("todo") || lower.contains("project") || lower.contains("kanban") || lower.contains("work") -> Archetype.TASK_PRODUCTIVITY
            else -> Archetype.TASK_PRODUCTIVITY
        }
    }

    private fun extractAppName(prompt: String, archetype: Archetype): String {
        val namedMatch = Regex("""(?:named|called|name is)\s+["']?([A-Za-z0-9\s]{3,24})["']?""", RegexOption.IGNORE_CASE).find(prompt)
        if (namedMatch != null) {
            val candidate = namedMatch.groupValues[1].trim()
            if (candidate.isNotBlank()) return candidate
        }
        val firstWords = prompt.trim().split(Regex("\\s+")).take(3).joinToString(" ")
        if (firstWords.length in 4..24 && firstWords.all { it.isLetterOrDigit() || it.isWhitespace() }) {
            return firstWords.split(" ").joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        }
        return when (archetype) {
            Archetype.ECOMMERCE -> "ShopNova"
            Archetype.FOOD_DELIVERY -> "BiteDash"
            Archetype.CHAT_SOCIAL -> "PulseChat"
            Archetype.RIDE_SHARING -> "VeloRide"
            Archetype.TASK_PRODUCTIVITY -> "TaskFlow"
            Archetype.FITNESS_TRACKER -> "ApexFit"
            Archetype.NOTES_KNOWLEDGE -> "ZenNotes"
            Archetype.FINANCE_BUDGET -> "VaultFinance"
        }
    }

    private fun sanitizePackageName(appName: String): String {
        val clean = appName.lowercase().replace(Regex("[^a-z0-9]"), "")
        val suffix = if (clean.length < 3) "app" else clean
        return "com.pocketdev.$suffix"
    }

    private fun buildEcommerceSpec(): Triple<List<DataEntitySpec>, List<ApiEndpointSpec>, List<ScreenSpec>> {
        val entities = listOf(
            DataEntitySpec(
                name = "Product",
                tableName = "products",
                fields = listOf(
                    DataEntityField("id", "String", primaryKey = true),
                    DataEntityField("name", "String"),
                    DataEntityField("description", "String"),
                    DataEntityField("price", "Double"),
                    DataEntityField("category", "String"),
                    DataEntityField("imageUrl", "String"),
                    DataEntityField("inStock", "Boolean", defaultValue = "true")
                )
            ),
            DataEntitySpec(
                name = "CartItem",
                tableName = "cart_items",
                fields = listOf(
                    DataEntityField("id", "String", primaryKey = true),
                    DataEntityField("productId", "String"),
                    DataEntityField("quantity", "Int", defaultValue = "1"),
                    DataEntityField("unitPrice", "Double")
                )
            ),
            DataEntitySpec(
                name = "Order",
                tableName = "orders",
                fields = listOf(
                    DataEntityField("id", "String", primaryKey = true),
                    DataEntityField("totalAmount", "Double"),
                    DataEntityField("status", "String", defaultValue = "'PENDING'"),
                    DataEntityField("createdAt", "Long")
                )
            )
        )

        val endpoints = listOf(
            ApiEndpointSpec("GET", "/api/products", "List products with search and category filtering", responseModel = "List<Product>"),
            ApiEndpointSpec("GET", "/api/products/{id}", "Get product details", responseModel = "Product"),
            ApiEndpointSpec("POST", "/api/cart", "Add item to cart", requestBodyModel = "CartItem", responseModel = "CartItem"),
            ApiEndpointSpec("GET", "/api/cart", "Get active user cart", responseModel = "List<CartItem>"),
            ApiEndpointSpec("POST", "/api/orders", "Checkout and place order", requestBodyModel = "Order", responseModel = "Order"),
            ApiEndpointSpec("GET", "/api/orders", "List customer past orders", responseModel = "List<Order>")
        )

        val screens = listOf(
            ScreenSpec("CatalogScreen", "catalog", "Product Catalog", "Browse products with grid layout, category filters, and search bar", listOf("SearchBar", "CategoryChips", "ProductGrid", "CartBadgeFAB")),
            ScreenSpec("ProductDetailScreen", "product/{id}", "Product Details", "High-res images, pricing, specs, and Add to Cart action", listOf("ImageCarousel", "PriceTag", "StockBadge", "AddToCartButton")),
            ScreenSpec("CartScreen", "cart", "Your Cart", "Cart item list with quantity adjusters, subtotal, and checkout CTA", listOf("CartList", "SubtotalSummary", "CheckoutButton")),
            ScreenSpec("OrderHistoryScreen", "orders", "Order History", "Chronological list of placed orders with status tracker", listOf("OrderCards", "StatusPill", "ReorderButton"))
        )

        return Triple(entities, endpoints, screens)
    }

    private fun buildFoodDeliverySpec(): Triple<List<DataEntitySpec>, List<ApiEndpointSpec>, List<ScreenSpec>> {
        val entities = listOf(
            DataEntitySpec(
                name = "Restaurant",
                tableName = "restaurants",
                fields = listOf(
                    DataEntityField("id", "String", primaryKey = true),
                    DataEntityField("name", "String"),
                    DataEntityField("cuisine", "String"),
                    DataEntityField("rating", "Double", defaultValue = "4.5"),
                    DataEntityField("deliveryTimeMinutes", "Int", defaultValue = "30"),
                    DataEntityField("bannerUrl", "String")
                )
            ),
            DataEntitySpec(
                name = "MenuItem",
                tableName = "menu_items",
                fields = listOf(
                    DataEntityField("id", "String", primaryKey = true),
                    DataEntityField("restaurantId", "String"),
                    DataEntityField("name", "String"),
                    DataEntityField("price", "Double"),
                    DataEntityField("isVegetarian", "Boolean", defaultValue = "false")
                )
            ),
            DataEntitySpec(
                name = "FoodOrder",
                tableName = "food_orders",
                fields = listOf(
                    DataEntityField("id", "String", primaryKey = true),
                    DataEntityField("restaurantId", "String"),
                    DataEntityField("totalPrice", "Double"),
                    DataEntityField("deliveryAddress", "String"),
                    DataEntityField("status", "String", defaultValue = "'PLACED'")
                )
            )
        )

        val endpoints = listOf(
            ApiEndpointSpec("GET", "/api/restaurants", "List nearby restaurants", responseModel = "List<Restaurant>"),
            ApiEndpointSpec("GET", "/api/restaurants/{id}/menu", "Get menu items for restaurant", responseModel = "List<MenuItem>"),
            ApiEndpointSpec("POST", "/api/food-orders", "Place food delivery order", requestBodyModel = "FoodOrder", responseModel = "FoodOrder"),
            ApiEndpointSpec("GET", "/api/food-orders/{id}/track", "Live order status and driver location", responseModel = "FoodOrder")
        )

        val screens = listOf(
            ScreenSpec("ExploreScreen", "explore", "Explore Eateries", "Featured carousel, cuisine tags, and restaurant cards", listOf("HeroBanner", "CuisineRow", "RestaurantList")),
            ScreenSpec("MenuScreen", "restaurant/{id}", "Menu & Dishes", "Categories, appetizing dish cards, customization dialog", listOf("RestaurantHeader", "DishSections", "StickyCartBar")),
            ScreenSpec("TrackingScreen", "track/{orderId}", "Live Order Tracker", "Step progress bar, estimated arrival timer, driver call button", listOf("LiveMapPlaceholder", "ProgressBar", "DriverContactCard"))
        )

        return Triple(entities, endpoints, screens)
    }

    private fun buildChatSocialSpec(): Triple<List<DataEntitySpec>, List<ApiEndpointSpec>, List<ScreenSpec>> {
        val entities = listOf(
            DataEntitySpec(
                name = "User",
                tableName = "users",
                fields = listOf(
                    DataEntityField("id", "String", primaryKey = true),
                    DataEntityField("username", "String"),
                    DataEntityField("displayName", "String"),
                    DataEntityField("avatarUrl", "String"),
                    DataEntityField("statusMessage", "String")
                )
            ),
            DataEntitySpec(
                name = "Message",
                tableName = "messages",
                fields = listOf(
                    DataEntityField("id", "String", primaryKey = true),
                    DataEntityField("conversationId", "String"),
                    DataEntityField("senderId", "String"),
                    DataEntityField("text", "String"),
                    DataEntityField("timestamp", "Long"),
                    DataEntityField("isRead", "Boolean", defaultValue = "false")
                )
            )
        )

        val endpoints = listOf(
            ApiEndpointSpec("GET", "/api/conversations", "List user conversations", responseModel = "List<Conversation>"),
            ApiEndpointSpec("GET", "/api/conversations/{id}/messages", "Get conversation messages", responseModel = "List<Message>"),
            ApiEndpointSpec("POST", "/api/messages", "Send new message", requestBodyModel = "Message", responseModel = "Message")
        )

        val screens = listOf(
            ScreenSpec("InboxScreen", "inbox", "Messages", "Active chat threads, online badges, search filter", listOf("SearchBar", "StoryAvatars", "ThreadList")),
            ScreenSpec("ChatRoomScreen", "chat/{id}", "Conversation", "Message bubbles, attachment menu, text composer with voice note", listOf("ChatHeader", "BubbleList", "MessageComposer")),
            ScreenSpec("ProfileScreen", "profile", "User Profile", "Avatar, bio, notifications toggle, privacy settings", listOf("ProfileAvatar", "BioCard", "SettingsToggles"))
        )

        return Triple(entities, endpoints, screens)
    }

    private fun buildRideSharingSpec(): Triple<List<DataEntitySpec>, List<ApiEndpointSpec>, List<ScreenSpec>> {
        val entities = listOf(
            DataEntitySpec(
                name = "RideRequest",
                tableName = "ride_requests",
                fields = listOf(
                    DataEntityField("id", "String", primaryKey = true),
                    DataEntityField("passengerId", "String"),
                    DataEntityField("pickupAddress", "String"),
                    DataEntityField("dropoffAddress", "String"),
                    DataEntityField("fareEstimate", "Double"),
                    DataEntityField("rideType", "String", defaultValue = "'STANDARD'"),
                    DataEntityField("status", "String", defaultValue = "'SEARCHING'")
                )
            )
        )

        val endpoints = listOf(
            ApiEndpointSpec("POST", "/api/rides/estimate", "Estimate fare and pickup time", responseModel = "FareEstimate"),
            ApiEndpointSpec("POST", "/api/rides/request", "Request a ride", requestBodyModel = "RideRequest", responseModel = "RideRequest"),
            ApiEndpointSpec("GET", "/api/rides/{id}", "Get live ride status", responseModel = "RideRequest")
        )

        val screens = listOf(
            ScreenSpec("BookingScreen", "booking", "Book a Ride", "Destination search, vehicle tier selector, price preview", listOf("AddressInput", "TierSelector", "ConfirmRideButton")),
            ScreenSpec("ActiveTripScreen", "trip/{id}", "En Route", "Driver details, vehicle plate, ETA countdown, safety tools", listOf("DriverCard", "TripEtaBadge", "CancelOrShareButton"))
        )

        return Triple(entities, endpoints, screens)
    }

    private fun buildTaskProductivitySpec(): Triple<List<DataEntitySpec>, List<ApiEndpointSpec>, List<ScreenSpec>> {
        val entities = listOf(
            DataEntitySpec(
                name = "TaskItem",
                tableName = "tasks",
                fields = listOf(
                    DataEntityField("id", "String", primaryKey = true),
                    DataEntityField("title", "String"),
                    DataEntityField("notes", "String"),
                    DataEntityField("isCompleted", "Boolean", defaultValue = "false"),
                    DataEntityField("priority", "String", defaultValue = "'MEDIUM'"),
                    DataEntityField("dueDate", "Long"),
                    DataEntityField("category", "String", defaultValue = "'General'")
                )
            )
        )

        val endpoints = listOf(
            ApiEndpointSpec("GET", "/api/tasks", "List tasks with filter and sorting", responseModel = "List<TaskItem>"),
            ApiEndpointSpec("POST", "/api/tasks", "Create task", requestBodyModel = "TaskItem", responseModel = "TaskItem"),
            ApiEndpointSpec("PUT", "/api/tasks/{id}", "Update task state", requestBodyModel = "TaskItem", responseModel = "TaskItem"),
            ApiEndpointSpec("DELETE", "/api/tasks/{id}", "Delete task", responseModel = "Boolean")
        )

        val screens = listOf(
            ScreenSpec("TasksScreen", "tasks", "My Tasks", "Categorized task list with swipe-to-complete, sorting, and stats", listOf("CategoryTabs", "TaskItemRows", "QuickAddBar")),
            ScreenSpec("TaskDetailScreen", "task/{id}", "Edit Task", "Title input, markdown notes, date/time picker, priority tags", listOf("TitleField", "PrioritySegmentedControl", "DatePicker")),
            ScreenSpec("StatsScreen", "stats", "Productivity Insights", "Completion charts, weekly streak counter, category breakdown", listOf("StreakCard", "WeeklyBarChart", "CategoryPieChart"))
        )

        return Triple(entities, endpoints, screens)
    }

    private fun buildFitnessTrackerSpec(): Triple<List<DataEntitySpec>, List<ApiEndpointSpec>, List<ScreenSpec>> {
        val entities = listOf(
            DataEntitySpec(
                name = "WorkoutSession",
                tableName = "workouts",
                fields = listOf(
                    DataEntityField("id", "String", primaryKey = true),
                    DataEntityField("name", "String"),
                    DataEntityField("durationSeconds", "Int", defaultValue = "0"),
                    DataEntityField("caloriesBurned", "Int", defaultValue = "0"),
                    DataEntityField("date", "Long")
                )
            )
        )

        val endpoints = listOf(
            ApiEndpointSpec("GET", "/api/workouts", "List past workouts", responseModel = "List<WorkoutSession>"),
            ApiEndpointSpec("POST", "/api/workouts", "Record completed workout", requestBodyModel = "WorkoutSession", responseModel = "WorkoutSession")
        )

        val screens = listOf(
            ScreenSpec("DashboardScreen", "dashboard", "Fitness Hub", "Today's calorie ring, start workout button, quick stats", listOf("CalorieRing", "StreakBanner", "StartWorkoutFab")),
            ScreenSpec("WorkoutScreen", "workout/active", "Live Workout", "Timer, exercise rep logger, rest interval timer", listOf("TimerDisplay", "ExerciseCard", "RestTimer"))
        )

        return Triple(entities, endpoints, screens)
    }

    private fun buildNotesSpec(): Triple<List<DataEntitySpec>, List<ApiEndpointSpec>, List<ScreenSpec>> {
        val entities = listOf(
            DataEntitySpec(
                name = "Note",
                tableName = "notes",
                fields = listOf(
                    DataEntityField("id", "String", primaryKey = true),
                    DataEntityField("title", "String"),
                    DataEntityField("content", "String"),
                    DataEntityField("folder", "String", defaultValue = "'General'"),
                    DataEntityField("isPinned", "Boolean", defaultValue = "false"),
                    DataEntityField("updatedAt", "Long")
                )
            )
        )

        val endpoints = listOf(
            ApiEndpointSpec("GET", "/api/notes", "List notes", responseModel = "List<Note>"),
            ApiEndpointSpec("POST", "/api/notes", "Save note", requestBodyModel = "Note", responseModel = "Note"),
            ApiEndpointSpec("DELETE", "/api/notes/{id}", "Delete note", responseModel = "Boolean")
        )

        val screens = listOf(
            ScreenSpec("NotesListScreen", "notes", "All Notes", "Staggered grid of notes, search filter, folder drawer", listOf("NotesGrid", "PinnedSection", "SearchField")),
            ScreenSpec("EditorScreen", "notes/{id}", "Note Editor", "Markdown editor with live preview, word count, export", listOf("FormattingToolbar", "EditorArea", "WordCounter"))
        )

        return Triple(entities, endpoints, screens)
    }

    private fun buildFinanceBudgetSpec(): Triple<List<DataEntitySpec>, List<ApiEndpointSpec>, List<ScreenSpec>> {
        val entities = listOf(
            DataEntitySpec(
                name = "Transaction",
                tableName = "transactions",
                fields = listOf(
                    DataEntityField("id", "String", primaryKey = true),
                    DataEntityField("title", "String"),
                    DataEntityField("amount", "Double"),
                    DataEntityField("isExpense", "Boolean", defaultValue = "true"),
                    DataEntityField("category", "String"),
                    DataEntityField("date", "Long")
                )
            )
        )

        val endpoints = listOf(
            ApiEndpointSpec("GET", "/api/transactions", "List transactions", responseModel = "List<Transaction>"),
            ApiEndpointSpec("POST", "/api/transactions", "Record transaction", requestBodyModel = "Transaction", responseModel = "Transaction")
        )

        val screens = listOf(
            ScreenSpec("FinanceHome", "finance", "Money Overview", "Account balance card, income vs expense bar, recent entries", listOf("BalanceCard", "QuickAddButtons", "RecentTxList")),
            ScreenSpec("AddTxScreen", "finance/add", "Add Transaction", "Numpad, category grid, expense/income toggle", listOf("AmountInput", "CategoryGrid", "SaveButton"))
        )

        return Triple(entities, endpoints, screens)
    }
}
