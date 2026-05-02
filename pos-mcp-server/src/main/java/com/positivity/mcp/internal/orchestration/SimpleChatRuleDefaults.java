package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.enums.SimpleChatRuleType;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;

public final class SimpleChatRuleDefaults {

    private SimpleChatRuleDefaults() {}

    public static @NonNull List<SimpleChatRuleSpec> defaults() {
        return Stream.of(
                        specs(SimpleChatRuleType.GREETING, "en", 100, "hi", "hello", "hey", "good morning",
                                "good afternoon", "good evening"),
                        specs(SimpleChatRuleType.GREETING, "fr", 100, "bonjour", "salut", "bonsoir"),
                        specs(SimpleChatRuleType.GREETING, "es", 100, "hola", "buenos días", "buenas tardes",
                                "buenas noches"),
                        specs(SimpleChatRuleType.THANKS, "en", 200, "thanks", "thank you"),
                        specs(SimpleChatRuleType.THANKS, "fr", 200, "merci"),
                        specs(SimpleChatRuleType.THANKS, "es", 200, "gracias", "muchas gracias"),
                        specs(SimpleChatRuleType.SOCIAL_QUESTION, "en", 300, "how are you", "how's it going",
                                "how is it going"),
                        specs(SimpleChatRuleType.SOCIAL_QUESTION, "fr", 300, "comment ça va"),
                        specs(SimpleChatRuleType.SOCIAL_QUESTION, "es", 300, "cómo estás"),
                        specs(SimpleChatRuleType.SAY_HELLO_TARGET, "en", 400, "hello", "hi", "hey"),
                        specs(SimpleChatRuleType.SAY_HELLO_TARGET, "fr", 400, "bonjour", "salut"),
                        specs(SimpleChatRuleType.SAY_HELLO_TARGET, "es", 400, "hola"),
                        specs(SimpleChatRuleType.CAPABILITY, "en", 500, "who are you", "what can you do", "help"),
                        specs(SimpleChatRuleType.TASK_REQUEST_PHRASE, "en", 600, "can you", "could you", "would you",
                                "help me", "i need", "i want", "show me", "tell me", "look up", "search for"),
                        specs(SimpleChatRuleType.TASK_REQUEST_PHRASE, "fr", 600, "peux-tu", "pouvez-vous",
                                "s'il vous plaît", "svp"),
                        specs(SimpleChatRuleType.TASK_REQUEST_PHRASE, "es", 600, "por favor"),
                        specs(SimpleChatRuleType.TASK_QUANTITY_PREFIX, "en", 700, "how many", "how much"),
                        specs(SimpleChatRuleType.TASK_QUANTITY_PREFIX, "fr", 700, "combien", "combien de", "combien d"),
                        specs(SimpleChatRuleType.TASK_QUANTITY_PREFIX, "es", 700, "cuánto", "cuántos", "cuántas"),
                        specs(SimpleChatRuleType.BUSINESS_KEYWORD, "en", 800, "accounting", "audit", "catalog",
                                "customer", "employee", "event", "invoice", "inventory", "location", "order", "part",
                                "payment", "price", "product", "report", "sales", "shop", "sku", "stock", "tax",
                                "vehicle", "vin", "workorder"),
                        specs(SimpleChatRuleType.BUSINESS_KEYWORD, "fr", 800, "comptabilité", "audit", "catalogue",
                                "client", "employé", "événement", "facture", "inventaire", "localisation",
                                "commande", "pièce", "paiement", "prix", "produit", "rapport", "ventes", "magasin",
                                "stock", "taxe", "véhicule"),
                        specs(SimpleChatRuleType.BUSINESS_KEYWORD, "es", 800, "contabilidad", "auditoría", "catálogo",
                                "cliente", "empleado", "evento", "factura", "inventario", "ubicación", "pedido",
                                "orden", "pieza", "pago", "precio", "producto", "informe", "reporte", "ventas",
                                "tienda", "taller", "existencias", "impuesto", "vehículo"),
                        specs(SimpleChatRuleType.ACTION_KEYWORD, "en", 900, "calculate", "check", "create", "delete",
                                "find", "get", "list", "lookup", "search", "show", "summarize", "update"),
                        specs(SimpleChatRuleType.ACTION_KEYWORD, "fr", 900, "calculer", "vérifier", "créer",
                                "supprimer", "trouver", "obtenir", "lister", "consulter", "rechercher", "montrer",
                                "résumer", "actualiser"),
                        specs(SimpleChatRuleType.ACTION_KEYWORD, "es", 900, "calcular", "revisar", "comprobar",
                                "crear", "eliminar", "borrar", "buscar", "encontrar", "obtener", "listar", "mostrar",
                                "resumir", "actualizar"),
                        specs(SimpleChatRuleType.TASK_CUE_KEYWORD, "en", 1000, "describe", "details", "document",
                                "docs", "explain", "guide", "manual", "need", "please", "policy", "procedure",
                                "status", "want"),
                        specs(SimpleChatRuleType.TASK_CUE_KEYWORD, "fr", 1000, "décrire", "document", "documentation",
                                "expliquer", "manuel", "besoin", "politique", "procédure", "statut", "veux"),
                        specs(SimpleChatRuleType.TASK_CUE_KEYWORD, "es", 1000, "describir", "detalle", "detalles",
                                "documento", "explicar", "guía", "necesito", "política", "procedimiento", "estado",
                                "quiero"))
                .flatMap(List::stream)
                .toList();
    }

    public static @NonNull SimpleChatRuleCatalog defaultCatalog() {
        return SimpleChatRuleCatalog.from(defaults());
    }

    private static @NonNull List<SimpleChatRuleSpec> specs(
            @NonNull SimpleChatRuleType ruleType,
            @NonNull String languageCode,
            int priority,
            @NonNull String... phrases) {
        return Arrays.stream(phrases)
                .map(phrase -> new SimpleChatRuleSpec(ruleType, phrase, languageCode, priority))
                .toList();
    }
}
