/*
 * LEDs driver for firefly-rk3288
 *
 * Created on: 2015/11/19
 * Author: qiyei2009	<1273482124@qq.com>
 * Copyright (C) 2015 CBPM-KX  chengdu,sichuan province in china
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as
 * published by the Free Software Foundation.
 *
 */
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/platform_device.h>
#include <linux/gpio.h>
#include <linux/leds.h>
#include <linux/of_platform.h>
#include <linux/of_gpio.h>
#include <linux/slab.h>
#include <linux/workqueue.h>
#include <linux/module.h>
#include <linux/pinctrl/consumer.h>
#include <linux/err.h>
#include <linux/device.h>
#include <linux/cdev.h>
#include <linux/fs.h>
#include <linux/delay.h>
#include <asm/uaccess.h>
#include <asm/irq.h>
#include <asm/io.h>

#define DEVICE_NAME "firefly-led"

/*����led �豸������ݽṹ��*/
struct gpio_led_info {
	struct platform_device	*pdev;
	int     power_gpio;
	int     power_enable_value;
	int     work_gpio;
	int     work_enable_value;
};

struct gpio_led_info *firefly_led;

static struct cdev led_cdev;

static int led_major = 0;
static int led_minor = 0;

static int num_of_led = 1;

static dev_t dev_nu = 0;

static struct class *led_class;
static struct device *led_device;

struct device_node *led_node;


static int firefly_led_open(struct inode *inode, struct file *filp)
{
	int ret = -1;
	
	int gpio,flag;

	
	led_node = of_find_node_by_name(NULL," firefly-led");//���LED���豸�ڵ�
	if (led_node = NULL)
		return -1;
	
	/*�ҵ�power led*/
	gpio = of_get_named_gpio_flags(led_node, "led_power", 0, ( enum of_gpio_flags *)&flag);
	
	if(!gpio_is_valid(gpio)){
		printk(KERN_ERR"get gpio is error !%d\n",gpio);
		return -1;
	}

	ret = gpio_request(gpio,"led-power");
	
	if(ret){
		printk(KERN_ERR"gpio request is error !%d\n",ret);
		
		goto gpio_request_fail;
	}

	firefly_led->power_gpio = gpio;
	firefly_led->power_enable_value = (flag == OF_GPIO_ACTIVE_LOW)? 0:1;
	
	/*�ҵ�led-work*/
	gpio = of_get_named_gpio_flags(led_node, "led_work", 0, ( enum of_gpio_flags *)&flag);

	if(!gpio_is_valid(gpio)){
		printk(KERN_ERR"get gpio is error !%d\n",gpio);
		return -1;
	}

	ret = gpio_request(gpio,"led-work");
	
	if(ret){
		printk(KERN_ERR"gpio request is error !%d\n",ret);
		goto gpio_request_fail;
	}

	firefly_led->work_gpio = gpio;
	firefly_led->work_enable_value = (flag == OF_GPIO_ACTIVE_LOW)? 0:1;

	
	filp->private_data = firefly_led;
		
	return ret;
	
gpio_request_fail:
	
	gpio_free(gpio);
	ret = -EIO;
	return ret;
}


static int firefly_led_release(struct inode *inode, struct file *filp)
{

	gpio_free(firefly_led->work_gpio);
	gpio_free(firefly_led->power_gpio);

	return 0;
}


static long firefly_led_ioctl(struct file *filp, unsigned int command, unsigned long arg)
{

	struct gpio_led_info *led = filp->private_data; //��ȡ�豸�ṹ��

	switch(arg){
	case 0:
		if (command == 0)
			gpio_direction_output(led->power_gpio, !led->power_enable_value);//�͵�ƽ��
		else
			gpio_direction_output(led->power_gpio, led->power_enable_value);//�͵�ƽ��	
		break;

	case 1:
		if (command == 0)
			gpio_direction_output(led->work_gpio,!led->work_enable_value);//�͵�ƽ��
		else
			gpio_direction_output(led->work_gpio,led->work_enable_value);//�͵�ƽ��
		break;
		
	default:
		return -EINVAL;
		break;
	}

	return 0;
			

}



static const struct file_operations led_fops = {
	.open		= firefly_led_open,
	.release	         = firefly_led_release,
	.unlocked_ioctl =firefly_led_ioctl,
	.owner		= THIS_MODULE,
};


static int __init led_init(void)
{

	int result, i;
	
	
	//�����豸��
	/*
 	* Get a range of minor numbers to work with, asking for a dynamic
 	* major unless directed otherwise at load time.
 	*/
	if (led_major) {
		dev_nu = MKDEV(led_major, led_minor);
		result = register_chrdev_region(dev_nu, num_of_led,DEVICE_NAME);
	} else {
		result = alloc_chrdev_region(&dev_nu, led_minor,num_of_led,DEVICE_NAME);
		led_major = MAJOR(dev_nu);
	}
	if (result < 0) {
		printk(KERN_ERR "firefly-gpio-led: can't get major %d\n",led_major);
		return result;
	}

	firefly_led = kzalloc(sizeof(struct gpio_led_info),GFP_KERNEL);
	if(!firefly_led){
		result = -1;
		goto fail_malloc;
	}

	//��ʼ��cdev �ṹ��
	cdev_init(&led_cdev, &led_fops);
	led_cdev.owner = THIS_MODULE;
	led_cdev.ops = & led_fops;


	//ע��cdev
	result = cdev_add (&led_cdev, dev_nu, 1);
	/* Fail gracefully if need be */
	if (result)
		printk(KERN_NOTICE "Error %d adding led_cdev", result);

		/* ����led_drv�� */
	led_class = class_create(THIS_MODULE,DEVICE_NAME);

	/* ��led_drv���´���/dev/LED�豸����Ӧ�ó�����豸*/
	led_device = device_create(led_class, NULL, dev_nu, NULL, DEVICE_NAME);

	
	return result;


fail_malloc:
	unregister_chrdev_region(dev_nu, 1);
	return result;

}



static void __exit led_exit(void)
{
	
	cdev_del(&led_cdev);
	kfree(firefly_led);
	unregister_chrdev_region(dev_nu, 1);
	
}


/* 	�����������/���ں��������仰˵���൱��
 * 	�����ں�������������/���ں���������
 */
module_init(led_init);
module_exit(led_exit);


MODULE_AUTHOR("qiyei2009	<1273482124@qq.com>");
MODULE_DESCRIPTION(" LED driver for firefly-rk3288");
MODULE_LICENSE("GPL");
MODULE_ALIAS("leds-gpio");

