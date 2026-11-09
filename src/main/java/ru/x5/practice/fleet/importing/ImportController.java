package ru.x5.practice.fleet.importing;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;
import ru.x5.practice.fleet.ApiError;

@RestController
public class ImportController {

    private final ImportService importer;

    public ImportController(ImportService importer) {
        this.importer = importer;
    }

    /**
     * Multipart разбирается лениво (spring.servlet.multipart.resolve-lazily): неизвестный набор
     * проверяется раньше наличия файла, а битое тело даёт FILE_REQUIRED, а не 500.
     */
    @PostMapping("/import/{dataset}")
    public Map<String, Object> importDataset(@PathVariable String dataset, HttpServletRequest request) {
        if (!Datasets.ALL.containsKey(dataset)) {
            throw ImportService.unknownDataset(dataset);
        }
        MultipartHttpServletRequest multipart =
                WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        // файл — часть multipart с filename; текстовое поле file файлом не считается
        MultipartFile file = multipart == null ? null : multipart.getFile("file");
        if (file == null) {
            throw new ApiError(400, "FILE_REQUIRED", "multipart field 'file' is required");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new ApiError(400, "FILE_REQUIRED", "file could not be read");
        }
        return importer.importCsv(dataset, content);
    }
}
