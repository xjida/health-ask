package com.hebeu.ask.spider.processor;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.hebeu.ask.model.po.SpiderKeyword;
import com.hebeu.ask.spider.service.SpiderKeywordService;
import com.hebeu.ask.spider.util.ApplicationContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import us.codecraft.webmagic.*;
import us.codecraft.webmagic.downloader.HttpClientDownloader;
import us.codecraft.webmagic.processor.PageProcessor;
import us.codecraft.webmagic.proxy.Proxy;
import us.codecraft.webmagic.proxy.ProxyProvider;
import us.codecraft.webmagic.selector.Selectable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author : chenDeHua
 * Time   : 2018/3/11 下午9:14
 * Desc   : 360问答processor
 **/
@Service
@Slf4j
public class QihuSpiderProcessor implements PageProcessor{

    /**
     * 部分一：抓取网站的相关配置，包括编码、抓取间隔、重试次数等
     */
    private Site site = Site.me().setRetryTimes(3).setSleepTime(1000);
    /**
     * 网址根路径
     */
    private static final String ROOT = "https://wenda.so.com";

    /**
     * 匿名网友昵称
     */
    private static final String ANONYMOUS_USER = "匿名网友";

    @Autowired
    private SpiderKeywordService spiderKeywordService;


    @Override
    public void process(Page page) {
        log.info("page.html():{}", page.getHtml());
        String pageUrl = page.getUrl().get();
        log.info("爬取网址 pageUrl:{}", pageUrl);
        // 问题列表URL
        String questionListUrl = "http://wenda.so.com/search";
        // 问题详情URL
        String questionDetailUrl = "http://wenda.so.com/q";

        if (pageUrl.startsWith(questionListUrl)) {

            final Map<String, Object> extras = page.getRequest().getExtras();

            List<Selectable> questionList = page.getHtml().xpath("//*/li[@class=\"item\"]").nodes();

            log.info("questionList:{}", questionList);
            List<String> questionUrls = Lists.newLinkedList();
            if (!CollectionUtils.isEmpty(questionList)) {
                for (Selectable question : questionList) {
                    String url = question.xpath("//*/a/@href").get();
                    questionUrls.add(url);
                }
            }

            if (!CollectionUtils.isEmpty(questionUrls)) {
                questionUrls.forEach(url -> {
                    Request request = new Request(ROOT + url);
                    request.setExtras(extras);
                    page.addTargetRequest(request);
                });
            }

            if(spiderKeywordService == null) {
                this.spiderKeywordService = ApplicationContextUtil.getBean(SpiderKeywordService.class);
            }
            //保证一定传递，忽略了检查
            int keywordId = (Integer) extras.get("keywordId");
            int categoryId = (Integer) extras.get("categoryId");
            String keyword = extras.get("keyword").toString();
            log.info("keyword:{};keywordId:{};categoryId:{}", keyword, keywordId, categoryId);
            int index = pageUrl.indexOf("pn=");
            log.info("pn=  index:{}", index);
            // TODO 后期再修改
            int maxPageNum = 10;
            if (index >= 0) {
                int pageNum = Integer.parseInt(pageUrl.substring(index + 3));
                if (pageNum == 0) {

                    spiderKeywordService.updateCrawlStatusById(keywordId, 1);
                    String url = "https://wenda.so.com/search/?q=" + keyword + "&pn=" + (pageNum + 1);
                    Request request = new Request(url);
                    request.setExtras(extras);
                    page.addTargetRequest(request);
                } else if (pageNum == maxPageNum) {
                    spiderKeywordService.updateCrawlStatusById(keywordId, 2);

                    SpiderKeyword askSpiderKeyword = spiderKeywordService.findNextUnCrawled();
                    if (askSpiderKeyword == null) {
                        log.info("all keywords have been crawled");
                        return;
                    }

                    keyword = askSpiderKeyword.getKeyword();
                    String url = "https://wenda.so.com/search/?q=" + keyword + "&pn=0";
                    Request request = new Request(url);
                    Map<String, Object> data = Maps.newHashMap();
                    data.put("keyword", keyword);
                    data.put("keywordId", askSpiderKeyword.getId());
                    data.put("categoryId", askSpiderKeyword.getCategoryId());
                    request.setExtras(data);
                    page.addTargetRequest(request);
                } else {
                    String url = "https://wenda.so.com/search/?q=" + keyword + "&pn=" + (pageNum + 1);
                    Request request = new Request(url);
                    request.setExtras(extras);
                    page.addTargetRequest(request);
                }
            }
        } else if (pageUrl.startsWith(questionDetailUrl)) {
            //TODO 爬取详情页
            this.parseQuestionPage(page);
        }
    }



    private void parseQuestionPage(Page page) {
        log.info("开始分析问题页");
        String keyword = page.getRequest().getExtra("keyword").toString();
        int categoryId = Integer.parseInt(page.getRequest().getExtra("categoryId").toString());

        Selectable selectable = page.getHtml();

        String title = selectable.xpath("//*/input[@id=\"js-ask-title\"]/@value").get();
        List<Selectable> authorNodes = selectable.xpath("//*/div[contains(@class,\"q-info\")]/span").nodes();
        String author = "";
        String date = "";
        int answers = 0;
        if (!CollectionUtils.isEmpty(authorNodes) && authorNodes.size() >= 3) {
            author = authorNodes.get(0).xpath("span/text()").get();
            author = (StringUtils.isEmpty(author) ? ANONYMOUS_USER : author).trim();
            date = authorNodes.get(1).xpath("span/text()").get().trim();
            String strAnswers = authorNodes.get(2).xpath("span/text()").get();
            int index = strAnswers.indexOf("个回答");
            if (index >= 0) {
                strAnswers = strAnswers.substring(0, index);
            }
            try {
                answers = Integer.parseInt(strAnswers);
            } catch (Exception e) {

            }
        }

        String content = selectable.xpath("//*/div[@class=\"q-con\"]/text()").get();
        content = (content == null ? "" : content);

        log.info("content:{}", content);
        if (!StringUtils.isEmpty(author) && !StringUtils.isEmpty(content) && !StringUtils.isEmpty(title)) {

//            List<AskSpiderTag> tags = this.findTags(title, content, bizType);
//            if (!CollectionUtils.isEmpty(tags)) {
//
//                //Step 1: 存用户信息，并拿到id
//                int authorId = this.insertOrUpdateUser(author);
//
//                //Step 2: 存文章信息并拿到id
//                //先判断文章是否已经存在
//                int questionId = this.queryQuestion(title, bizType);
//                boolean exists = questionId > 0;
//                if (!exists) {
//                    questionId = this.saveQuestion(authorId, title, content, author, bizType, date, answers, categoryId);
//
//                    //向 ask_doing表插入数据
//                    this.increaseQuestionCount(authorId);
//                }
//                //Step 3: 存文章——url——记录
//                if (!exists) {
//                    this.saveSpiderLog(questionId, page.getUrl().get(), "360问答");
//                }
//                //Step 4: 存文章--KEYWORD--对应关系 (一篇文章可能有多个keyword，后面可以洗keyword为tag)
//                this.saveQuestionKeywordRel(questionId, keyword);
//
//                //Step 5: 存回答
//                if (!exists) {
//                    this.saveAnswers(questionId, page);
//                }
//
//                //Step 6: 存相关问题（文章ID对应title) 并继续爬取相关问题
//                this.saveRelatedQuestions(questionId, page);
//
//                //Step 7: 存文章id和tag的关系
//                this.saveQuestionTagRel(questionId, tags);
//            }
        }

        log.info("crawled url: " + page.getRequest().getUrl());
    }

    @Override
    public Site getSite() {
        return site;
    }


    public void doRun() {
        SpiderKeyword spiderKeyword = this.spiderKeywordService.findNextUnCrawled();
        if(spiderKeyword == null) {
            log.info("all keywords have been crawled");
            return;
        }

        String keyword = spiderKeyword.getKeyword();
        String url = "https://wenda.so.com/search/?q=虚劳&pn=0";
        Request request = new Request(url);
        Map<String, Object> extras = Maps.newHashMap();
        extras.put("keyword", keyword);
        extras.put("keywordId", spiderKeyword.getId());
        extras.put("categoryId", spiderKeyword.getCategoryId());
        log.info("keyword:{};keywordId:{};categoryId:{}", keyword,  spiderKeyword.getId(), spiderKeyword.getCategoryId());
        request.setExtras(extras);

        HttpClientDownloader downloader = new HttpClientDownloader();
        downloader.setThread(3);


        // 爬虫程序入口
        Spider.create(new QihuSpiderProcessor())
                .setExitWhenComplete(true)
                .setUUID(UUID.randomUUID().toString())
                .setDownloader(downloader)
                .addRequest(request)
                .run();

    }

}
