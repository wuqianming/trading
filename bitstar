#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import decimal
import requests
import json
import time

from bitstar_sdk import ApiClient

API_KEY = ''
API_SECRET = ''


def main():
    client = ApiClient(API_KEY, API_SECRET)

    response = requests.get('https://www.bitstar.com/api/v1/market/ticker/swap-btc-cny')
    ticker = json.loads(response.text)
    buy_1 = ticker['buy']
    sell_1 = ticker['sell']
    price = buy_1 + (sell_1 - buy_1)/2

    i = 1
    while(i<10):
        print(price)
        trade = client.trade('swap-btc-cny', 2, price, 100)
        print(trade)
        trade = client.trade('swap-btc-cny', 1, price, 100)
        print(trade)
        trade = client.trade('swap-btc-cny', 4, price, 100)
        print(trade)
        trade = client.trade('swap-btc-cny', 3, price, 100)
        print(trade)

        time.sleep(1)

        i = i+1

if __name__ == '__main__':
    main()
