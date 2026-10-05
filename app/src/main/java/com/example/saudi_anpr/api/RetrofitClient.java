package com.example.saudi_anpr.api;

import android.util.Log;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {

    private static final String TAG = "ANPR_RETROFIT";

    // ⚠️ IMPORTANT: Update this to your CURRENT Ngrok URL or Local IP
    // Example Ngrok: "https://f20e-103-211-39-113.ngrok-free.app/"
    // Example Local:  "http://192.168.1.100:8000/"
    public static final String BASE_URL = "https://parakeet-talented-newt.ngrok-free.app/";

    private static Retrofit retrofit;

    public static AnprApiService getService() {
        if (retrofit == null) {
            // Logging Interceptor to see exactly what is sent and received
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor(message ->
                    Log.d(TAG, "HTTP: " + message)
            );
            logging.setLevel(HttpLoggingInterceptor.Level.BODY);

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .addInterceptor(logging)
                    .build();

            retrofit = new Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();

            Log.d(TAG, "Retrofit initialized with BASE_URL: " + BASE_URL);
        }
        return retrofit.create(AnprApiService.class);
    }
}
