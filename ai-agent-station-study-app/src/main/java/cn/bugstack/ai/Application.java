package cn.bugstack.ai;

import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@Configurable
@EnableTransactionManagement
public class Application {

    public static void main(String[] args){
        SpringApplication.run(Application.class);
    }

}
