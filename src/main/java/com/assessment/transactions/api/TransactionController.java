package com.assessment.transactions.api;

import com.assessment.transactions.model.*;
import com.assessment.transactions.service.TransactionProcessingService;
import com.assessment.transactions.service.TransactionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class TransactionController {
    private final TransactionProcessingService service;
    public TransactionController(TransactionProcessingService service){this.service=service;}

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TransactionRecord submit(@Valid @RequestBody TransactionRequest request){return service.submit(request);}

    @ExceptionHandler(TransactionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleConflict(TransactionConflictException ex) {
        Map<String, String> result = new HashMap<String, String>();
        result.put("error", "Transaction conflict");
        result.put("message", ex.getMessage());
        return result;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("error", "Validation failed");
        result.put("details", ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.toList()));
        return ResponseEntity.badRequest().body(result);
    }

    @GetMapping("/transactions")
    public List<TransactionRecord> all(){return service.getAll();}

    @GetMapping("/transactions/{id}")
    public TransactionRecord get(@PathVariable String id){
        TransactionRecord r=service.get(id);
        if(r==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Transaction not found");
        return r;
    }

    @GetMapping("/accounts/{accountId}/balance")
    public Map<String,Object> balance(@PathVariable String accountId){
        Map<String,Object> result=new HashMap<String,Object>();
        result.put("accountId",accountId); result.put("balance",service.getBalance(accountId)); return result;
    }

    @GetMapping("/operations/summary")
    public ProcessingSummary summary(){return service.summary();}
}
