package local.printer.order;

import java.time.LocalDate;

/** 对外白名单；交易与型号的演示属性分开，不暴露客户或认证信息。 */
public record OrderResponse(long id, String orderNo, String modelCode, String brand,
                            String serialNo, LocalDate purchaseDate, boolean orderDemo, boolean modelDemo) {}
