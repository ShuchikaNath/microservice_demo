package com.microservices.orderservice.service;

import com.microservices.orderservice.dto.InventoryResponse;
import com.microservices.orderservice.dto.OrderLineItemsDto;
import com.microservices.orderservice.dto.OrderRequest;
import com.microservices.orderservice.event.OrderPlacedEvent;
import com.microservices.orderservice.model.Order;
import com.microservices.orderservice.model.OrderLineItems;
import com.microservices.orderservice.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.transaction.Transactional;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private WebClient.Builder webClientBuilder;
    @Autowired
    private  KafkaTemplate<String,OrderPlacedEvent> kafkaTemplate;

    public OrderService() {
    }

    public String placeOrder(OrderRequest orderRequest){

        Order order = new Order();
        order.setOrderNumber(UUID.randomUUID().toString());

        List<OrderLineItems> orderLineItemsList = orderRequest.getOrderLineItemsDtoList()
                .stream()
                .map(this::mapToDto).collect(Collectors.toList());

        order.setOrderLineItemsList(orderLineItemsList);

      List<String> skuCodes = order.getOrderLineItemsList().stream().map(OrderLineItems::getSkuCode).collect(Collectors.toList());

       //Call inventory service and place order if product is in stock
        //to be able to read data from the response we use method : bodyToMono(<data type of response>)
        //by default web client makes an asynchronous request ,
        // to make synchronous request to inventory service  add method block()

        InventoryResponse[] inventoryResponsesArray = webClientBuilder.build().get().uri("http://inventory-service/api/inventory",
                        uriBuilder -> uriBuilder.queryParam("skuCode",skuCodes).build())
                        .retrieve().bodyToMono(InventoryResponse[].class)
                        .block();

       boolean allProductsInStock =  Arrays.stream(inventoryResponsesArray).allMatch(InventoryResponse::isInStock);

        if(allProductsInStock){
            orderRepository.save(order);
            //making a call to Kafka cluster whenever an order is placed
            kafkaTemplate.send("notificationTopic",new OrderPlacedEvent(order.getOrderNumber()));
            return "Order placed successfully !!!";
        }
        else{
            throw new IllegalArgumentException("Product is not in stock, please try again later");
        }

    }

    private OrderLineItems mapToDto(OrderLineItemsDto orderLineItemsDto) {

        OrderLineItems orderLineItems = new OrderLineItems();

        orderLineItems.setPrice(orderLineItemsDto.getPrice());
        orderLineItems.setQuantity(orderLineItemsDto.getQuantity());
        orderLineItems.setSkuCode(orderLineItemsDto.getSkuCode());

        return orderLineItems;
    }
}
