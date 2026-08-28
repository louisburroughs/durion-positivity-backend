package com.positivity.bulkloader.internal.domain;

/**
 * One loader record together with the line of the uploaded file it came from.
 *
 * <p>Row numbers are assigned once, by the processor, and carried to the writer rather than
 * recounted there. Counting in the writer only works while every row that is read also gets
 * written: the moment the processor drops one — a validation failure, an unresolvable business key
 * — the writer's own count runs ahead of the file, and every audit row after the first skip names
 * the wrong line. That is the number an operator uses to find the offending row, so it has to be
 * the one the reader actually saw.
 *
 * @param rowNumber zero-based index of the record's row among the file's data rows
 * @param record the mapped record
 */
public record NumberedRecord<T>(long rowNumber, T record) {}
