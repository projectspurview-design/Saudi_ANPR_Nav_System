package com.example.saudi_anpr.api;

import okhttp3.MultipartBody;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface AnprApiService {

    @Multipart
    @POST("/api/detect")
    Call<DetectResponse> detectPlate(@Part MultipartBody.Part file);

    // NEW: Fetch schedule for reminders
    @GET("/api/schedule")
    Call<ScheduleResponse> getSchedule();
}