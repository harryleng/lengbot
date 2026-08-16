package com.lengbot.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import jakarta.servlet.http.HttpServletRequest;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * SSE 端点（produces=text/event-stream）上发生异常时，Spring 会强制用 SSE 协议写出响应体，
     * 而此处返回的是 JSON（Result），导致 {@code HttpMessageNotWritableException: No converter for
     * [class Result] with preset Content-Type 'text/event-stream'} —— 真正的业务异常被淹没，
     * 前端反而收到 500 + 乱流。因此在 SSE 场景下改吐 {@code data:[STATUS]{type:error}} 格式，
     * 前端 processSseLines 能优雅识别为 error 事件并提示，而非被翻译成"AI 大模型调用失败"。
     */
    private boolean isSseRequest(HttpServletRequest request) {
        if (request == null) return false;
        String accept = request.getHeader("Accept");
        String contentType = request.getContentType();
        return (accept != null && accept.contains("text/event-stream"))
                || (contentType != null && contentType.contains("text/event-stream"));
    }

    @ExceptionHandler(NotLoginException.class)
    public Object handleNotLogin(NotLoginException e, HttpServletRequest request) {
        log.info("未登录访问: {}", e.getMessage());
        if (isSseRequest(request)) {
            // 走 SSE 协议：让前端识别为 error 事件（type=auth_expired），而不是 500 乱流
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
                    .body("data:[STATUS]" + "{\"type\":\"auth_expired\",\"message\":\"登录已过期，请重新登录\"}");
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.fail(401, "未登录或登录已过期"));
    }

    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<Result<Void>> handleNotRole(NotRoleException e) {
        log.warn("角色校验失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.fail(403, "无权访问，需要" + e.getRole() + "角色"));
    }

    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<Result<Void>> handleNotPermission(NotPermissionException e) {
        log.warn("权限校验失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.fail(403, "无权访问"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst().orElse("参数校验失败");
        log.info("参数校验失败: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(400, message));
    }

    @ExceptionHandler(org.springframework.validation.BindException.class)
    public ResponseEntity<Result<Void>> handleBind(org.springframework.validation.BindException e) {
        String message = e.getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst().orElse("参数绑定失败");
        log.info("参数绑定失败: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(400, message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraint(ConstraintViolationException e) {
        log.info("约束校验失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(400, e.getMessage()));
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus())
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        log.info("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(400, "请求体格式错误"));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<Void>> handleNoHandler(NoHandlerFoundException e) {
        log.info("接口不存在: {} {}", e.getHttpMethod(), e.getRequestURL());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(404, "接口不存在"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        log.info("资源不存在: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(404, "资源不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException e) {
        log.info("请求方法不支持: method={}", e.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(405, "请求方法不支持"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException e) {
        log.info("不支持的媒体类型: {}", e.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Result.fail(415, "不支持的Content-Type"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.info("上传文件过大: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(400, "上传文件大小超过限制"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        // 路径变量转换失败视为资源不存在（如超长/非法 ID 无法匹配任何资源），复用前端"资源不存在"识别逻辑
        boolean pathVariable = e.getParameter().hasParameterAnnotation(PathVariable.class);
        if (pathVariable) {
            log.info("路径参数无效: name={}, value={}", e.getName(), e.getValue());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Result.fail(404, "资源不存在"));
        }
        log.info("请求参数格式错误: name={}, value={}", e.getName(), e.getValue());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(400, "请求参数格式错误"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(500, "系统内部错误"));
    }
}
