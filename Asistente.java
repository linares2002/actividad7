package org.example;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// OkHttp
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

// Base de datos - java.sql
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

// Java util
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Asistente {
    private static final String API_KEY = "";
    private static final String DB_URL  =
            "jdbc:postgresql://localhost:5432/prueba";
    private static final String MODELO  = "claude-haiku-4-5-20251001";

    /**
     * Obtiene TODOS los datos de la semana completa de PostgreSQL.
     */
    private static String obtenerDatosSemana() throws Exception {
        StringBuilder sb = new StringBuilder();

        try (Connection conn = DriverManager.getConnection(
                DB_URL, "postgres", "oretania")) {

            String sql = "SELECT temp, time FROM dht11 " +
                    "ORDER BY time ASC";

            PreparedStatement ps = conn.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();

            int contador = 0;
            while (rs.next()) {
                sb.append(rs.getTime("time"))
                        .append(" -> ")
                        .append(String.format("%.2f", rs.getDouble("temp")))
                        .append("°C\n");
                contador++;
            }

            System.out.println("Registros cargados: " + contador);
        }

        return sb.toString();
    }

    /**
     * Construye el prompt especifico para predecir
     * las temperaturas de un dia completo.
     * Le pedimos a Claude 144 predicciones
     * (24 horas x 6 mediciones por hora = 144 valores).
     */
    private static String construirPromptPrediccion(String datos) {

        return "Eres un experto en analisis de series temporales IoT.\n\n"
                + "Tienes los siguientes datos de temperatura de un sensor "
                + "durante los ultimos 7 dias, con una medicion cada 10 minutos:\n\n"
                + "=== DATOS HISTORICOS ===\n"
                + datos + "\n"
                + "=== TAREA ===\n"
                + "Analiza los patrones de temperatura de la semana "
                + "(franjas horarias, diferencias entre dias, tendencias) "
                + "y predice las temperaturas para las proximas 24 horas "
                + "con intervalos de 10 minutos.\n\n"
                + "Devuelve la prediccion en este formato EXACTO, "
                + "una linea por cada intervalo de 10 minutos:\n"
                + "HH:MM -> XX.X°C\n\n"
                + "Empieza desde la hora actual y continua durante 24 horas. "
                + "Despues del listado, añade un breve resumen de la tendencia "
                + "detectada y el nivel de confianza de la prediccion.";
    }

    /**
     * Llama a la API de Claude con el prompt de prediccion.
     * Aumentamos max_tokens a 4096 porque la respuesta
     * va a ser larga (144 lineas de prediccion).
     */
    private static String predecirConClaude(String datos) throws Exception {

        // Timeout mas largo porque el prompt es grande
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60,  TimeUnit.SECONDS)
                .build();

        String prompt   = construirPromptPrediccion(datos);

        String jsonBody = new Gson().toJson(Map.of(
                "model",      MODELO,
                "max_tokens", 4096,        // mas tokens para respuesta larga
                "messages",   List.of(Map.of(
                        "role",    "user",
                        "content", prompt
                ))
        ));

        Request request = new Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .post(RequestBody.create(
                        jsonBody,
                        MediaType.get("application/json")))
                .addHeader("x-api-key",           API_KEY)
                .addHeader("anthropic-version",   "2023-06-01")
                .build();

        System.out.println("Enviando datos a Claude...");

        Response response = client.newCall(request).execute();

        String responseBody = response.body().string();

        JsonObject json = JsonParser.parseString(responseBody)
                .getAsJsonObject();

        if (json.has("error")) {
            String error = json.getAsJsonObject("error")
                    .get("message").getAsString();
            return "Error de Claude: " + error;
        }

        return json.getAsJsonArray("content")
                .get(0).getAsJsonObject()
                .get("text").getAsString();
    }

    public static void main(String[] args) throws Exception {
        // 1. Cargamos los datos historicos de la semana
        System.out.println("Leyendo datos de PostgreSQL...");
        String datosSemana = obtenerDatosSemana();

        if (datosSemana.isEmpty()) {
            System.out.println("ERROR: No hay datos en la base de datos.");
            return;
        }

        // 2. Pedimos la prediccion a Claude
        String prediccion = predecirConClaude(datosSemana);

        // 3. Mostramos el resultado
        System.out.println("  PREDICCION PARA LAS PROXIMAS 24 HORAS");
        System.out.println(prediccion);
    }
}