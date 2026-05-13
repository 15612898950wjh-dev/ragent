/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.config;

import io.minio.MinioClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 配置类
 * <p>
 * 用于配置 MinIO 客户端连接
 */
@Configuration
public class MinIOConfig {

    /**
     * 创建 MinIO 客户端 Bean
     */
    @Bean
    public MinioClient minioClient(MinIOProperties properties) {
        try {
            return MinioClient.builder()
                    .endpoint(properties.getUrl())
                    .credentials(properties.getAccessKeyId(), properties.getSecretAccessKey())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create MinIO client", e);
        }
    }

    /**
     * 注册配置属性 Bean
     */
    @Bean
    public MinIOProperties minioProperties() {
        return new MinIOProperties();
    }

    /**
     * MinIO 配置属性
     * <p>
     * 在 application.yml 中配置:
     * <pre>
     * minio:
     *   url: http://localhost:9000
     *   access-key-id: minioadmin
     *   secret-access-key: minioadmin
     * </pre>
     */
    @Data
    @ConfigurationProperties(prefix = "minio")
    public static class MinIOProperties {
        /**
         * MinIO 服务端点（包含协议、主机和端口）
         */
        private String url;

        /**
         * 访问密钥（Access Key）
         */
        private String accessKeyId;

        /**
         * 秘密密钥（Secret Key）
         */
        private String secretAccessKey;
    }
}
