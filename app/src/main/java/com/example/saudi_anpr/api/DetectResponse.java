package com.example.saudi_anpr.api;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class DetectResponse {
    @SerializedName("success")
    public boolean success;

    @SerializedName("plates")
    public List<String> plates;

    @SerializedName("plate_count")
    public int plateCount;

    @SerializedName("image_base64")
    public String imageBase64;

    @SerializedName("timestamp")
    public String timestamp;

    @SerializedName("error")
    public String error;
}