package com.positivity.documents.internal.service.format;

import com.opencsv.CSVReader;
import com.positivity.documents.internal.config.PdfConfiguration;
import com.positivity.documents.internal.enums.DocumentFormat;
import com.positivity.documents.internal.exception.RenderingException;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

@Component
public class CsvFormatHandler implements FormatHandler {

    private final PdfConfiguration pdfConfiguration;

    public CsvFormatHandler(PdfConfiguration pdfConfiguration) {
        this.pdfConfiguration = pdfConfiguration;
    }

    @Override
    public String processContent(String rawContent, Map<String, Object> context) {
        try (CSVReader reader = new CSVReader(new StringReader(rawContent))) {
            List<String[]> rows = reader.readAll();
            if (rows.isEmpty()) {
                throw RenderingException.malformedInput("CSV payload is empty");
            }

            context.put("rowCount", rows.size());
            context.put("renderedRowCount", Math.min(rows.size() - 1, pdfConfiguration.maxTableRows()));
            StringBuilder builder = new StringBuilder("<section><h2>CSV Content</h2><table><thead><tr>");
            String[] header = rows.get(0);
            for (String column : header) {
                builder.append("<th>").append(HtmlUtils.htmlEscape(column)).append("</th>");
            }
            builder.append("</tr></thead><tbody>");

            int maxRows = Math.min(rows.size() - 1, pdfConfiguration.maxTableRows());
            for (int rowIndex = 1; rowIndex <= maxRows; rowIndex++) {
                builder.append("<tr>");
                String[] row = rows.get(rowIndex);
                for (String cell : row) {
                    builder.append("<td>").append(HtmlUtils.htmlEscape(cell)).append("</td>");
                }
                builder.append("</tr>");
            }

            builder.append("</tbody></table></section>");
            int omittedRows = (rows.size() - 1) - maxRows;
            if (omittedRows > 0) {
                builder.append("<p><em>")
                        .append(omittedRows)
                        .append(" rows omitted for performance. Full dataset size: ")
                        .append(rows.size() - 1)
                        .append(" rows.</em></p>");
            }
            return builder.toString();
        } catch (RenderingException ex) {
            throw ex;
        } catch (Exception ex) {
            throw RenderingException.malformedInput("Malformed CSV payload", ex);
        }
    }

    @Override
    public DocumentFormat supportedFormat() {
        return DocumentFormat.CSV;
    }
}
