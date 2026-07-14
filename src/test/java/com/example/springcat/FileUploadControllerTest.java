package com.example.springcat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FileUploadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void uploadsFileAndReportsSize() throws Exception {
        byte[] content = "hello springcat".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "greeting.txt", "text/plain", content);

        mockMvc.perform(multipart("/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalName").value("greeting.txt"))
                .andExpect(jsonPath("$.sizeBytes").value(content.length));
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        mockMvc.perform(multipart("/upload").file(file))
                .andExpect(status().isBadRequest());
    }
}
