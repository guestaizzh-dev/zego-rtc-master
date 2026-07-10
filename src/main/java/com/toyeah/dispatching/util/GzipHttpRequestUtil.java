package com.toyeah.dispatching.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

public class GzipHttpRequestUtil {
    private static final Logger log = LoggerFactory.getLogger(GzipHttpRequestUtil.class);

    public static String sendGetRequest(String urlString) {
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setUseCaches(false);
            connection.setDoInput(true);

            // 当服务器使用GZIP压缩时，需要解压
            String encoding = connection.getContentEncoding();
            if (encoding != null && encoding.contains("gzip")) {
                inputStream = new GZIPInputStream(connection.getInputStream());
            } else {
                inputStream = connection.getInputStream();
            }

            reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        } catch (IOException e) {
            log.error("获取Gzip结果的http接口发生IO异常！",e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    log.error("关闭Gzip-reader失败！",e);
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    log.error("关闭Gzip-inputStream失败！",e);
                }
            }
        }
    }
}
