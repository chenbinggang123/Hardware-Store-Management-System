package com.example.demo.agent;
import com.example.demo.agent.api.ServerMediaController;
import com.example.demo.agent.entity.AgentAttachment;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.service.BillingPhotoService;
import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.config.AuthInterceptor;
import com.example.demo.entity.AppUser;
import com.example.demo.repository.ProductRepository;
import com.example.demo.security.AuthSession;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ServerMediaControllerTest {
 final AttachmentStorage storage=mock(AttachmentStorage.class);
 final AgentAttachmentRepository attachments=mock(AgentAttachmentRepository.class);
 final BillingPhotoService photos=mock(BillingPhotoService.class);
 final ProductRepository products=mock(ProductRepository.class);
 final ServerMediaController controller=new ServerMediaController(storage,attachments,photos,products);
 MockHttpServletRequest request(long id){var request=new MockHttpServletRequest();var user=new AppUser();user.setId(id);request.setAttribute(AuthInterceptor.AUTH_SESSION_ATTRIBUTE,new AuthSession("test",user));return request;}
 @Test void uploadRejectsDisguisedHtmlAndOversizedImages(){assertThrows(Exception.class,()->controller.upload(new MockMultipartFile("file","x.jpg","image/jpeg","<html>".getBytes()),request(1)));verifyNoInteractions(storage);}
 @Test void uploadUsesServerStorage() throws Exception {controller.upload(new MockMultipartFile("file","x.jpg","image/jpeg",new byte[]{(byte)255,(byte)216,(byte)255}),request(1));verify(storage).put(startsWith("media/product-images/1/"),any(byte[].class),eq("image/jpeg"));}
 @Test void productDeletionRequiresOwnerAndRetainsReferencedFile(){assertThrows(Exception.class,()->controller.delete(2L,"00000000-0000-0000-0000-000000000000.jpg",request(1)));verifyNoInteractions(storage);}
 @Test void privatePhotoCannotBeReadByOtherUserUnlessPosted() throws Exception {
  var file=new AgentAttachment();file.setId(9L);file.setOperatorId(1L);file.setMimeType("image/jpeg");file.setObjectKey("agent/1/x.jpg");when(attachments.findById(9L)).thenReturn(Optional.of(file));
  assertThrows(Exception.class,()->controller.attachment(9L,request(2)));verify(storage,never()).openStream(anyString());
  when(photos.isPostedPhoto(9L)).thenReturn(true);when(storage.openStream("agent/1/x.jpg")).thenReturn(new ByteArrayInputStream(new byte[]{1,2}));assertEquals(2,controller.attachment(9L,request(2)).getBody().length);
 }
 @Test void traversalRejected(){assertThrows(Exception.class,()->controller.product(1L,"../../secret",request(1)));verifyNoInteractions(storage);}
}
