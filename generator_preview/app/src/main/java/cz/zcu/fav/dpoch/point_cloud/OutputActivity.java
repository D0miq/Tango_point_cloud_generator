package cz.zcu.fav.dpoch.point_cloud;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.TextView;

public class OutputActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_output);
        Intent i = getIntent();
        String message = i.getStringExtra("MESSAGE");
        TextView et = (TextView) findViewById(R.id.outputText);
        et.setText(message);
    }
}
