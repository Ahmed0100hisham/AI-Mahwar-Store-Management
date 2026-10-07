package com.almahwar.api.manager;

import com.almahwar.api.error.FieldValidationException;
import com.almahwar.api.web.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;

/** Phase 1 paging convention: zero-based page, default 20, maximum 100. Validated for direct calls too. */
public record ManagerPage(
        @Schema(description="Zero-based, 0..10000; default 0") Integer page,
        @Schema(description="1..100; default 20") Integer size,
        @Schema(description="Literal search, maximum 100 characters") String q,
        @Schema(description="Endpoint-specific allowlist, optional ,asc or ,desc") String sort) {
    public PageQuery paging() {
        try { return new PageQuery(page==null?0:page,size==null?PageQuery.DEFAULT_SIZE:size); }
        catch (IllegalArgumentException e) { throw new FieldValidationException("page","Invalid page/size."); }
    }
    public String search() {
        if (q!=null && q.length()>100) throw new FieldValidationException("q","Maximum 100 characters.");
        return q==null || q.isBlank()?null:q.strip();
    }
    public void validate(String... fields) {
        paging();search();
        var names=java.util.Arrays.stream(fields).collect(java.util.stream.Collectors.toMap(f->f,f->f));
        order(names,fields[0]);
    }
    public String order(java.util.Map<String,String> fields, String defaultSort) {
        String value=sort==null?defaultSort:sort;
        if(value.length()>40) throw new FieldValidationException("sort","Invalid sort.");
        String[] parts=value.split(",",-1);
        String field=fields.get(parts[0]);
        if (field==null || parts.length>2 || (parts.length==2 && !parts[1].equals("asc") && !parts[1].equals("desc")))
            throw new FieldValidationException("sort","Invalid sort.");
        return field + (parts.length==2 && parts[1].equals("desc")?" DESC":" ASC");
    }
}
