package com.example.demo.agent.api;

import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.service.BillingPhotoService;
import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.common.ApiResponse;
import com.example.demo.config.AuthInterceptor;
import com.example.demo.repository.ProductRepository;
import com.example.demo.security.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import static org.springframework.http.HttpStatus.*;

/** Authenticated file endpoints; no public storage directory or credential in image URLs. */
@RestController
public class ServerMediaController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.demo.agent.repository.AgentRunRepository runs;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.demo.billing.BillingDocumentRepository documents;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.demo.filecenter.repository.FileCenterMetadataRepository metadata;
    @PostMapping("/agent/runs/{id}/attachments/cleanup")
    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<?> cleanup(@PathVariable Long id,HttpServletRequest request) {
        Long owner=owner(request);
        var run=runs.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        if(!owner.equals(run.getOperatorId())) throw new ResponseStatusException(FORBIDDEN);
        if(!java.util.Set.of(com.example.demo.agent.harness.AgentRunStatus.COMPLETED,
                com.example.demo.agent.harness.AgentRunStatus.FAILED,
                com.example.demo.agent.harness.AgentRunStatus.CANCELLED,
                com.example.demo.agent.harness.AgentRunStatus.EXPIRED).contains(run.getStatus()))
            throw new ResponseStatusException(CONFLICT,"任务仍在处理中");
        for(var file:attachments.findByRunIdOrderByCreateTimeAsc(id)) {
            if(!owner.equals(file.getOperatorId()) || !documents.findReferencing(file.getId()).isEmpty()) continue;
            if(metadata.findByOperatorIdAndSourceKindAndSourceId(owner,"ATTACHMENT",file.getId())
                    .map(item -> Boolean.TRUE.equals(item.getManualUpload())).orElse(false)) continue;
            if(file.getObjectKey()!=null && !file.getObjectKey().startsWith("cloud://")) storage.delete(file.getObjectKey());
        }
        return ApiResponse.ok(Map.of("cleaned",true));
    }
    private final AttachmentStorage storage;
    private final AgentAttachmentRepository attachments;
    private final BillingPhotoService photos;
    private final ProductRepository products;
    public ServerMediaController(AttachmentStorage storage, AgentAttachmentRepository attachments,
                                 BillingPhotoService photos, ProductRepository products) {
        this.storage=storage;this.attachments=attachments;this.photos=photos;this.products=products;
    }
    private Long owner(HttpServletRequest request) {
        var value=request.getAttribute(AuthInterceptor.AUTH_SESSION_ATTRIBUTE);
        if (!(value instanceof AuthSession session)) throw new ResponseStatusException(UNAUTHORIZED);
        return session.getUser().getId();
    }
    private String productKey(Long owner, String name) {
        if(owner == null || owner <= 0 || !name.matches("[0-9a-f-]{36}\\.(jpg|png|webp)"))
            throw new ResponseStatusException(BAD_REQUEST,"图片路径无效");
        return "media/product-images/"+owner+"/"+name;
    }
    @PostMapping(value="/media/product-images", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> upload(@RequestPart("file") MultipartFile file,HttpServletRequest request) throws IOException {
        Long owner=owner(request);
        if(file.isEmpty() || file.getSize()>3*1024*1024) throw new ResponseStatusException(BAD_REQUEST,"商品图片须在3MB以内");
        byte[] bytes=file.getBytes();String ext;
        if(bytes.length>=3 && (bytes[0]&255)==255 && (bytes[1]&255)==216 && (bytes[2]&255)==255) ext="jpg";
        else if(bytes.length>=8 && bytes[0]==(byte)137 && bytes[1]==80 && bytes[2]==78 && bytes[3]==71 && bytes[4]==13 && bytes[5]==10 && bytes[6]==26 && bytes[7]==10) ext="png";
        else if(bytes.length>=12 && new String(bytes,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF") && new String(bytes,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) ext="webp";
        else throw new ResponseStatusException(BAD_REQUEST,"请选择JPG、PNG或WebP图片");
        String key=productKey(owner,UUID.randomUUID()+"."+ext);
        storage.put(key,bytes,"image/"+(ext.equals("jpg")?"jpeg":ext));
        return ApiResponse.ok(Map.of("fileID","server://"+key));
    }
    @GetMapping("/media/product-images/{userId}/{name}")
    public ResponseEntity<byte[]> product(@PathVariable Long userId,@PathVariable String name,HttpServletRequest request) throws IOException {
        owner(request);
        try(var input=storage.openStream(productKey(userId,name))) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("Cache-Control","private, no-store").body(input.readAllBytes());
        }
    }
    @DeleteMapping("/media/product-images/{userId}/{name}")
    public ApiResponse<?> delete(@PathVariable Long userId,@PathVariable String name,HttpServletRequest request) {
        if(!owner(request).equals(userId)) throw new ResponseStatusException(FORBIDDEN);
        String key=productKey(userId,name);
        if(products.findAll().stream().anyMatch(p -> ("server://"+key).equals(p.getImageUrl())))
            return ApiResponse.ok(Map.of("deleted",false));
        storage.delete(key);return ApiResponse.ok(Map.of("deleted",true));
    }
    @GetMapping("/agent/attachments/{id}/image")
    public ResponseEntity<byte[]> attachment(@PathVariable Long id,HttpServletRequest request) throws IOException {
        Long owner=owner(request);
        var file=attachments.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        if(!owner.equals(file.getOperatorId()) && !photos.isPostedPhoto(id)) throw new ResponseStatusException(FORBIDDEN);
        if(file.getMimeType()==null || !file.getMimeType().startsWith("image/") || file.getObjectKey().startsWith("cloud://"))
            throw new ResponseStatusException(NOT_FOUND);
        try(var input=storage.openStream(file.getObjectKey())) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("Cache-Control","private, no-store").body(input.readAllBytes());
        }
    }
}
