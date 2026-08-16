package com.lengbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Dify Dataset 连接器运行配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "lengbot.dify")
public class DifyProperties {

    /** Base64 编码的 32 字节 AES 密钥。 */
    private String encryptionKey;

    /** 密钥轮换标识。 */
    private String encryptionKeyVersion = "v1";

    private Integer connectTimeoutSeconds = 3;

    private Integer readTimeoutSeconds = 15;
}
