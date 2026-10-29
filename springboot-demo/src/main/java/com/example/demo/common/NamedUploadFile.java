package com.example.demo.common;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
/** Preserves the user-selected name when WeChat uploads a randomly named temporary file. */
public record NamedUploadFile(MultipartFile file, String originalName) implements MultipartFile {
    public static MultipartFile of(MultipartFile file,String name) { return name==null || name.isBlank() ? file : new NamedUploadFile(file,name); }
    @Override public String getName(){return file.getName();}
    @Override public String getOriginalFilename(){String name=originalName.replace('\\','/');return name.substring(name.lastIndexOf('/')+1);}
    @Override public String getContentType(){return file.getContentType();}
    @Override public boolean isEmpty(){return file.isEmpty();}
    @Override public long getSize(){return file.getSize();}
    @Override public byte[] getBytes() throws IOException {return file.getBytes();}
    @Override public InputStream getInputStream() throws IOException {return file.getInputStream();}
    @Override public void transferTo(File dest) throws IOException,IllegalStateException {file.transferTo(dest);}
}
