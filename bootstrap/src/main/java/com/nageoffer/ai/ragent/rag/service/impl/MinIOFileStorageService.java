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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.lang.Assert;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.rag.util.FileTypeDetector;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.tika.Tika;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * 基于 MinIO Java SDK 的文件存储服务实现
 * <p>
 * 替换原有的 AWS S3 SDK 实现，使用 MinIO 原生 SDK 提供更好的性能和更简单的 API
 */
@Service
@Primary
@RequiredArgsConstructor
public class MinIOFileStorageService implements FileStorageService {

    private final MinioClient minioClient;

    private static final Tika TIKA = new Tika();

    @Override
    @SneakyThrows
    public StoredFileDTO upload(String bucketName, MultipartFile file) {
        validateBucketName(bucketName);
        Assert.isFalse(file == null || file.isEmpty(), "上传文件不能为空");

        String originalFilename = file.getOriginalFilename();
        long size = file.getSize();

        // TIKA 只读流的前几 KB 来探测类型，不会加载整个文件
        String detectedContentType;
        try (InputStream is = file.getInputStream()) {
            detectedContentType = TIKA.detect(is, originalFilename);
        }

        // MultipartFile.getInputStream() 每次调用都返回新流（从底层临时文件重新打开），无需再创建临时文件
        try (InputStream is = file.getInputStream()) {
            return streamUploadToMinIO(bucketName, is, size, originalFilename, detectedContentType);
        }
    }

    @Override
    @SneakyThrows
    public StoredFileDTO upload(String bucketName, InputStream content, long size, String originalFilename, String contentType) {
        validateBucketName(bucketName);
        Assert.notNull(content, "上传内容不能为空");
        Assert.isTrue(size >= 0, "上传内容大小不能小于 0");
        String detected = resolveContentType(originalFilename, contentType);
        return streamUploadToMinIO(bucketName, content, size, originalFilename, detected);
    }

    @Override
    @SneakyThrows
    public StoredFileDTO upload(String bucketName, byte[] content, String originalFilename, String contentType) {
        validateBucketName(bucketName);
        Assert.notNull(content, "上传内容不能为空");
        String detected = resolveContentType(originalFilename, contentType);
        // byte[] 本身已在内存中，ByteArrayInputStream 不产生额外拷贝
        return streamUploadToMinIO(bucketName, new ByteArrayInputStream(content), content.length, originalFilename, detected);
    }

    @Override
    public InputStream openStream(String url) {
        MinIOLocation loc = parseMinIOUrl(url);
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(loc.bucket())
                            .object(loc.key())
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to get object from MinIO: " + url, e);
        }
    }

    @Override
    @SneakyThrows
    public void deleteByUrl(String url) {
        MinIOLocation loc = parseMinIOUrl(url);
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(loc.bucket())
                            .object(loc.key())
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete object from MinIO: " + url, e);
        }
    }

    @Override
    @SneakyThrows
    public StoredFileDTO reliableUpload(String bucketName, InputStream content, long size,
                                        String originalFilename, String contentType) {
        validateBucketName(bucketName);
        Assert.notNull(content, "上传内容不能为空");
        Assert.isTrue(size >= 0, "上传内容大小不能小于 0");
        String detected = resolveContentType(originalFilename, contentType);
        return streamUploadToMinIO(bucketName, content, size, originalFilename, detected);
    }

    /**
     * 使用 MinIO SDK 流式上传文件
     * <p>
     * MinIO Java SDK 的 putObject 方法原生支持 InputStream，
     * 可以高效地处理大文件上传，无需预签名 URL 的复杂逻辑
     */
    @SneakyThrows
    private StoredFileDTO streamUploadToMinIO(String bucketName, InputStream inputStream,
                                               long size, String originalFilename,
                                               String detectedContentType) {
        String minioKey = generateMinIOKey(originalFilename);

        try {
            // 使用 MinIO SDK 上传对象
            // partSize 默认 5MB，适合大文件分片上传
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(minioKey)
                            .contentType(detectedContentType)
                            .stream(inputStream, size, -1)  // -1 表示使用默认 partSize
                            .build()
            );
        } catch (Exception e) {
            throw new IOException("MinIO 上传失败: bucket=" + bucketName + ", key=" + minioKey, e);
        }

        // 构建返回结果
        String url = toMinIOUrl(bucketName, minioKey);
        return buildStoredFileDTO(url, originalFilename, detectedContentType, size);
    }

    private String toMinIOUrl(String bucket, String key) {
        return "s3://" + bucket + "/" + key;
    }

    private MinIOLocation parseMinIOUrl(String url) {
        // 保持与 S3 兼容的 URL 格式: s3://bucket/key
        if (!url.startsWith("s3://")) {
            throw new IllegalArgumentException("Unsupported url scheme: " + url + ", expected s3://");
        }
        String path = url.substring(5); // 去掉 "s3://"
        int slashIndex = path.indexOf('/');
        if (slashIndex < 0) {
            throw new IllegalArgumentException("Invalid MinIO url (key missing): " + url);
        }
        String bucket = path.substring(0, slashIndex);
        String key = path.substring(slashIndex + 1);
        if (bucket.isBlank()) {
            throw new IllegalArgumentException("Invalid MinIO url (bucket missing): " + url);
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("Invalid MinIO url (key missing): " + url);
        }
        return new MinIOLocation(bucket, key);
    }

    private record MinIOLocation(String bucket, String key) {
    }

    private String extractSuffix(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        return (idx < 0 || idx == filename.length() - 1) ? "" : filename.substring(idx + 1).trim();
    }

    private String generateMinIOKey(String originalFilename) {
        String suffix = extractSuffix(originalFilename);
        UUID uuid = UUID.randomUUID();
        String key = String.format("%016x%016x", uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
        return suffix.isBlank() ? key : key + "." + suffix;
    }

    private void validateBucketName(String bucketName) {
        Assert.notBlank(bucketName, "bucketName 不能为空");
    }

    private StoredFileDTO buildStoredFileDTO(String url, String originalFilename,
                                             String contentType, long size) {
        String detectedType = FileTypeDetector.detectType(originalFilename, contentType);
        return StoredFileDTO.builder()
                .url(url)
                .detectedType(detectedType)
                .size(size)
                .originalFilename(originalFilename)
                .build();
    }

    private String resolveContentType(String originalFilename, String contentType) {
        if (contentType != null && !contentType.isBlank()) return contentType;
        if (originalFilename != null && !originalFilename.isBlank()) return TIKA.detect(originalFilename);
        return null;
    }
}
