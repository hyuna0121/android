package com.example.hbook.network;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.example.hbook.model.TimestampEntry;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class ApiClient {

    private static final String BASE_URL = "https://perish-impure-hatred.ngrok-free.dev/";

    private static ApiService instance;

    private static class AuthInterceptor implements Interceptor {
        private final Context context;

        AuthInterceptor(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            SharedPreferences prefs =
                    context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE);
            String token = prefs.getString("auth_token", "");

            Request.Builder builder = chain.request().newBuilder()
                    .addHeader("ngrok-skip-browser-warning", "true");

            if (!token.isEmpty()) {
                builder.addHeader("Authorization", "Bearer " + token);
            }

            Response response = chain.proceed(builder.build());

            // 401 → 토큰 만료 → 자동 로그아웃
            if (response.code() == 401) {
                prefs.edit().clear().apply();
                Intent intent = new Intent(context,
                        com.example.hbook.ui.LoginActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                context.startActivity(intent);
            }

            return response;
        }
    }

    public static ApiService getService(Context context) {
        if (instance == null) {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(120, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.SECONDS)
                    .writeTimeout(120, TimeUnit.SECONDS)
                    .addInterceptor(new AuthInterceptor(context))
                    .build();

            com.google.gson.Gson gson = new GsonBuilder()
                    .registerTypeAdapter(
                            new TypeToken<List<TimestampEntry>>() {}.getType(),
                            (JsonDeserializer<List<TimestampEntry>>) (json, typeOfT, ctx) -> {
                                List<TimestampEntry> list = new ArrayList<>();
                                for (JsonElement el : json.getAsJsonArray())
                                    list.add(ctx.deserialize(el, TimestampEntry.class));
                                return list;
                            })
                    .create();

            instance = new Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create(gson))
                    .build()
                    .create(ApiService.class);
        }
        return instance;
    }

    public static void reset() { instance = null; }
}