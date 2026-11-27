#include "stm32f1xx_hal.h"

/* PA5=garland red, PA6=yellow, PA7=green (active high)
   PC0=traffic red, PC1=yellow, PC2=green (active low)
   PC3..PC9 = seven-segment A..G (assumed common cathode) */

static void SystemClock_Config(void);
static void MX_GPIO_Init(void);
void Error_Handler(void);

static const uint8_t seg_digits[10] =
 {0x3F,0x06,0x5B,0x4F,0x66,0x6D,0x7D,0x07,0x7F,0x6F};

static void display_digit(uint8_t n)
{
  uint16_t pins = GPIO_PIN_3|GPIO_PIN_4|GPIO_PIN_5|GPIO_PIN_6|
                  GPIO_PIN_7|GPIO_PIN_8|GPIO_PIN_9;
  uint16_t out = 0;
  if (n > 9) n = 0;
  if (seg_digits[n]&0x01) out |= GPIO_PIN_3;
  if (seg_digits[n]&0x02) out |= GPIO_PIN_4;
  if (seg_digits[n]&0x04) out |= GPIO_PIN_5;
  if (seg_digits[n]&0x08) out |= GPIO_PIN_6;
  if (seg_digits[n]&0x10) out |= GPIO_PIN_7;
  if (seg_digits[n]&0x20) out |= GPIO_PIN_8;
  if (seg_digits[n]&0x40) out |= GPIO_PIN_9;
  HAL_GPIO_WritePin(GPIOC,pins,GPIO_PIN_RESET);
  HAL_GPIO_WritePin(GPIOC,out,GPIO_PIN_SET);
}

int main(void)
{
  HAL_Init();
  SystemClock_Config();
  MX_GPIO_Init();

  uint32_t now=HAL_GetTick(), garlandBlinkAt=now, garlandPairAt=now;
  uint32_t trafficPhaseAt=now, trafficBlinkAt=now, displayAt=now;
  uint8_t pair=0, garlandOn=0, state=0, trafficBlink=1;
  /* state: green 5s, blinking green 2s, yellow 2s, red 5s */

  HAL_GPIO_WritePin(GPIOA,GPIO_PIN_5|GPIO_PIN_6|GPIO_PIN_7,GPIO_PIN_RESET);
  HAL_GPIO_WritePin(GPIOC,GPIO_PIN_0|GPIO_PIN_1|GPIO_PIN_2,GPIO_PIN_SET);
  display_digit(5);

  while (1) {
    now=HAL_GetTick();

    /* Garland pairs: R+Y, Y+G, G+R; 100ms ON/OFF; pair changes each second. */
    if ((uint32_t)(now-garlandBlinkAt)>=100) {
      garlandBlinkAt=now;
      garlandOn=!garlandOn;
      uint16_t p=(pair==0)?(GPIO_PIN_5|GPIO_PIN_6):
                 (pair==1)?(GPIO_PIN_6|GPIO_PIN_7):
                           (GPIO_PIN_7|GPIO_PIN_5);
      HAL_GPIO_WritePin(GPIOA,GPIO_PIN_5|GPIO_PIN_6|GPIO_PIN_7,GPIO_PIN_RESET);
      if (garlandOn) HAL_GPIO_WritePin(GPIOA,p,GPIO_PIN_SET);
    }
    if ((uint32_t)(now-garlandPairAt)>=1000) {
      garlandPairAt=now; pair=(pair+1)%3;
    }

    if (state==0 && (uint32_t)(now-trafficPhaseAt)>=5000) {
      state=1; trafficPhaseAt=now; trafficBlinkAt=now; trafficBlink=1;
    } else if (state==1 && (uint32_t)(now-trafficPhaseAt)>=2000) {
      state=2; trafficPhaseAt=now;
    } else if (state==2 && (uint32_t)(now-trafficPhaseAt)>=2000) {
      state=3; trafficPhaseAt=now;
    } else if (state==3 && (uint32_t)(now-trafficPhaseAt)>=5000) {
      state=0; trafficPhaseAt=now;
    }
    if (state==1 && (uint32_t)(now-trafficBlinkAt)>=250) {
      trafficBlinkAt=now; trafficBlink=!trafficBlink;
    }

    /* Traffic outputs are active-low. */
    HAL_GPIO_WritePin(GPIOC,GPIO_PIN_0|GPIO_PIN_1|GPIO_PIN_2,GPIO_PIN_SET);
    if (state==0 || (state==1 && trafficBlink))
      HAL_GPIO_WritePin(GPIOC,GPIO_PIN_2,GPIO_PIN_RESET);
    else if (state==2)
      HAL_GPIO_WritePin(GPIOC,GPIO_PIN_1,GPIO_PIN_RESET);
    else if (state==3)
      HAL_GPIO_WritePin(GPIOC,GPIO_PIN_0,GPIO_PIN_RESET);

    /* Display countdown belongs ONLY to traffic light, never the garland. */
    if ((uint32_t)(now-displayAt)>=100) {
      displayAt=now;
      uint32_t duration=(state==0 || state==3)?5000:2000;
      uint32_t elapsed=now-trafficPhaseAt;
      uint8_t remain=(elapsed>=duration)?0:(uint8_t)((duration-elapsed+999)/1000);
      display_digit(remain);
    }
  }
}

static void SystemClock_Config(void)
{
  RCC_OscInitTypeDef osc={0};
  RCC_ClkInitTypeDef clk={0};
  osc.OscillatorType=RCC_OSCILLATORTYPE_HSI;
  osc.HSIState=RCC_HSI_ON;
  osc.HSICalibrationValue=16;
  osc.PLL.PLLState=RCC_PLL_ON;
  osc.PLL.PLLSource=RCC_PLLSOURCE_HSI_DIV2;
  osc.PLL.PLLMUL=RCC_PLL_MUL4;
  if (HAL_RCC_OscConfig(&osc)!=HAL_OK) Error_Handler();
  clk.ClockType=RCC_CLOCKTYPE_HCLK|RCC_CLOCKTYPE_SYSCLK|
                RCC_CLOCKTYPE_PCLK1|RCC_CLOCKTYPE_PCLK2;
  clk.SYSCLKSource=RCC_SYSCLKSOURCE_PLLCLK;
  clk.AHBCLKDivider=RCC_SYSCLK_DIV2;
  clk.APB1CLKDivider=RCC_HCLK_DIV4;
  clk.APB2CLKDivider=RCC_HCLK_DIV2;
  if (HAL_RCC_ClockConfig(&clk,FLASH_LATENCY_0)!=HAL_OK) Error_Handler();
  HAL_SYSTICK_Config(HAL_RCC_GetHCLKFreq()/1000);
  HAL_SYSTICK_CLKSourceConfig(SYSTICK_CLKSOURCE_HCLK);
  HAL_NVIC_SetPriority(SysTick_IRQn,0,0);
}

static void MX_GPIO_Init(void)
{
  GPIO_InitTypeDef gpio={0};
  __HAL_RCC_GPIOA_CLK_ENABLE();
  __HAL_RCC_GPIOC_CLK_ENABLE();
  gpio.Mode=GPIO_MODE_OUTPUT_PP;
  gpio.Speed=GPIO_SPEED_FREQ_LOW;
  gpio.Pin=GPIO_PIN_5|GPIO_PIN_6|GPIO_PIN_7;
  HAL_GPIO_Init(GPIOA,&gpio);
  gpio.Pin=GPIO_PIN_0|GPIO_PIN_1|GPIO_PIN_2|GPIO_PIN_3|GPIO_PIN_4|
           GPIO_PIN_5|GPIO_PIN_6|GPIO_PIN_7|GPIO_PIN_8|GPIO_PIN_9;
  HAL_GPIO_Init(GPIOC,&gpio);
}

void Error_Handler(void)
{
  __disable_irq();
  while (1) {}
}
