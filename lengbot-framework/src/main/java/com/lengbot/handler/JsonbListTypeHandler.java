package com.lengbot.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * PostgreSQL JSONB 类型处理器（集合版）。
 *
 * <p>用于 {@code jsonb} 列映射到 {@code List<String>} 这类集合字段。相比 MyBatis-Plus 自带的
 * {@code JacksonTypeHandler}，本处理器通过 {@link PGobject} 显式把参数类型声明为 jsonb，
 * 避免 PostgreSQL 将 JSON 文本识别为 character varying 而报类型不匹配。</p>
 *
 * <p>仅处理字符串元素集合；需要对象元素时请另建处理器。</p>
 *
 * @author lw
 * @since 2026-11-04
 */
@MappedTypes(List.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonbListTypeHandler extends BaseTypeHandler<List<String>> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
            throws SQLException {
        if (parameter.isEmpty()) {
            ps.setNull(i, JdbcType.OTHER.TYPE_CODE);
            return;
        }
        PGobject pgObject = new PGobject();
        pgObject.setType("jsonb");
        try {
            pgObject.setValue(OBJECT_MAPPER.writeValueAsString(parameter));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SQLException("agent_ids 序列化失败: " + e.getMessage(), e);
        }
        ps.setObject(i, pgObject);
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(raw, TYPE);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }
}
